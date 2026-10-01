package advent.rag.agent

import advent.rag.embedding.EmbeddingClient
import advent.rag.knowledge.KnowledgeBase
import advent.rag.llm.ChatMessage
import advent.rag.llm.LlmClient
import advent.rag.retrieval.Retriever
import advent.rag.rewrite.QueryRewriter
import advent.rag.trace.*
import org.springframework.stereotype.Component
import kotlin.time.measureTimedValue

@Component
class Agent(
    private val llm: LlmClient, private val retriever: Retriever, private val embeddings: EmbeddingClient,
    private val knowledge: KnowledgeBase, private val prompts: PromptBuilder, private val router: RagRouter,
    private val journal: TraceJournal, private val rewriter: QueryRewriter,
) {
    fun ask(question: String, mode: String = "auto", history: List<ChatMessage> = emptyList(), rerank: Boolean? = null,
            rewrite: Boolean? = null): AgentAnswer {
        require(question.isNotBlank() && question.length <= 6000) { "Вопрос должен содержать от 1 до 6000 символов" }
        require(mode in listOf("auto", "rag", "plain")) { "Режим: auto, rag или plain" }
        require(history.size <= 12 && history.sumOf { it.content.length } <= 30000 &&
            history.all { it.role in listOf("user", "assistant") && it.content.isNotBlank() && it.content.length <= 10000 }) { "Некорректная история диалога" }
        val q = question.trim()
        val trace = TraceRecorder(mode, q)
        val useReranker = rerank ?: retriever.rerankEnabled
        try {
            val routing = router.decide(mode, q, history, knowledge.titles())
            trace += RouteStep(routing.decision, routing.completion?.exchange)
            val rewritten = if (routing.decision.useRag) rewriter.rewrite(q, history, rewrite).also {
                trace += RewriteStep(it.decision, it.completion?.exchange)
            } else null
            val retrieval = if (rewritten != null) {
                val search = rewritten.decision.query
                val found = retriever.search(search, useReranker)
                trace += EmbeddingStep.of(embeddings.model, found.query)
                trace += SearchStep.of(found)
                val selected = if (useReranker) retriever.rerank(search, found) else found
                trace += RerankStep.of(selected)
                selected
            } else null
            val (prompt, duration) = measureTimedValue { prompts.answer(q, history, retrieval?.chunks) }
            trace += PromptStep.of(prompt, q, duration)
            val completion = llm.complete(prompt)
            trace += LlmStep.of(completion)
            val cited = CITATIONS.findAll(completion.text).flatMap { it.groupValues[1].split(Regex("\\s*[,;]\\s*")).map(String::toIntOrNull).filterNotNull() }.toSet()
            val sources = retrieval?.chunks?.mapIndexed { i, found -> Source.of(i + 1, found, i + 1 in cited) }.orEmpty()
            val warnings = buildList {
                if (rewritten?.decision?.status == "fallback") add(rewritten.decision.reason + "; использована прежняя подготовка запроса")
                if (retrieval != null && cited.any { it !in 1..sources.size }) add("Модель указала номер источника, которого не было в контексте")
                if (retrieval != null && cited.isEmpty()) add("Ответ не содержит ссылок на фрагменты; его привязка к источникам не подтверждена")
                if (completion.finishReason == "length") add("Ответ достиг лимита токенов и может быть обрезан")
            }
            val answer = AgentAnswer(if (routing.decision.useRag) "rag" else "plain", q, completion.text, prompt,
                LlmCall.of(completion), trace.id, sources,
                (retrieval?.durationMs ?: 0) + (rewritten?.decision?.durationMs ?: 0),
                routing.decision, warnings, retrieval?.let(RerankInfo::of), rewritten?.decision)
            journal.save(trace.answered(answer.answer))
            return answer
        } catch (e: RuntimeException) { journal.save(trace.failed(e.message ?: "Ошибка агента")); throw e }
    }
    companion object {
        private val CITATIONS = Regex("\\[(\\d+(?:\\s*[,;]\\s*\\d+)*)]")
    }
}

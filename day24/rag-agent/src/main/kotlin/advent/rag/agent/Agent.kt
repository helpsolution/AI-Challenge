package advent.rag.agent

import advent.rag.embedding.EmbeddingClient
import advent.rag.grounding.GroundingChecker
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
    private val prompts: PromptBuilder, private val grounding: GroundingChecker, private val journal: TraceJournal,
    private val rewriter: QueryRewriter,
) {
    fun ask(question: String, history: List<ChatMessage> = emptyList()): AgentAnswer {
        require(question.isNotBlank() && question.length <= 6000) { "Вопрос должен содержать от 1 до 6000 символов" }
        require(history.size <= 12 && history.sumOf { it.content.length } <= 30000 &&
            history.all { it.role in listOf("user", "assistant") && it.content.isNotBlank() && it.content.length <= 10000 }) { "Некорректная история диалога" }
        val q = question.trim()
        val trace = TraceRecorder(q)
        try {
            val rewritten = rewriter.rewrite(q, history)
            trace += RewriteStep(rewritten.decision, rewritten.completion?.exchange)
            val retrieval = retriever.search(rewritten.decision.query)
            trace += EmbeddingStep.of(embeddings.model, retrieval.query)
            trace += SearchStep.of(retrieval)
            trace += RerankStep.of(retrieval)
            trace += ThresholdStep.of(retrieval)
            val context = retrieval.context
            val relevance = Relevance.of(retrieval)
            val unknown = AgentAnswer(q, AnswerStatus.WEAK_CONTEXT, UNKNOWN,
                "Ни один фрагмент не прошёл порог релевантности: лучшая оценка BGE %.2f ниже %.2f. Модель не вызывалась"
                    .format(relevance.bestScore, relevance.minScore),
                emptyList(), emptyList(), relevance, emptyList(), null, trace.id,
                retrieval.durationMs + rewritten.decision.durationMs, rewritten.decision,
                listOfNotNull(rewritten.decision.takeIf { it.status == "fallback" }?.let { it.reason + "; использована прежняя подготовка запроса" }))
            val answer = if (context.isEmpty()) unknown else {
                val (prompt, duration) = measureTimedValue { prompts.answer(q, history, context) }
                trace += PromptStep.of(prompt, q, duration)
                val completion = llm.complete(prompt, jsonResponse = true, maxTokens = 3000)
                trace += LlmStep.of(completion)
                val (checked, checkDuration) = measureTimedValue { grounding.check(completion, context) }
                trace += GroundingStep.of(checked, checkDuration)
                val called = unknown.copy(prompt = prompt, llm = LlmCall.of(completion))
                when (checked.status) {
                    AnswerStatus.ANSWERED -> {
                        val numbers = checked.quotes.map { it.number }.distinct().sorted()
                        called.copy(status = AnswerStatus.ANSWERED, answer = checked.answer!!,
                            reason = "Цитаты найдены дословно во фрагментах " + numbers.joinToString(" ") { "[$it]" },
                            sources = numbers.map { Source.of(it, context[it - 1]) }, quotes = checked.quotes)
                    }
                    AnswerStatus.NO_ANSWER -> called.copy(status = AnswerStatus.NO_ANSWER,
                        reason = "Модель не нашла ответа среди фрагментов, прошедших порог: ${context.size}")
                    else -> called.copy(status = AnswerStatus.UNVERIFIED,
                        reason = "Ответ модели отклонён проверкой цитат: " + checked.errors.joinToString("; "))
                }
            }
            journal.save(trace.answered(answer.status.code, answer.answer))
            return answer
        } catch (e: RuntimeException) { journal.save(trace.failed(e.message ?: "Ошибка агента")); throw e }
    }

    companion object {
        const val UNKNOWN = "Не знаю. Уточните вопрос."
    }
}

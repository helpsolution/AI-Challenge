package advent.rag.agent

import advent.rag.embedding.EmbeddingClient
import advent.rag.llm.LlmClient
import advent.rag.retrieval.Retriever
import advent.rag.trace.EmbeddingStep
import advent.rag.trace.LlmStep
import advent.rag.trace.PromptStep
import advent.rag.trace.SearchStep
import advent.rag.trace.TraceJournal
import advent.rag.trace.TraceRecorder
import org.springframework.stereotype.Component
import kotlin.time.measureTimedValue

/**
 * Агент с двумя режимами. Каждый вопрос отвечается отдельно, без истории диалога:
 * иначе ответ зависел бы от прошлых реплик, и режимы было бы не сравнить.
 * Каждый шаг записывается в трассировку — её показывает страница «Внутри агента».
 */
@Component
class Agent(
    private val llm: LlmClient,
    private val retriever: Retriever,
    private val embeddings: EmbeddingClient,
    private val journal: TraceJournal,
) {

    fun ask(question: String, useRag: Boolean): AgentAnswer {
        require(question.isNotBlank()) { "Вопрос пустой" }
        return if (useRag) answerWithRag(question.trim()) else answer(question.trim())
    }

    /** Без RAG: вопрос → запрос к LLM. */
    fun answer(question: String): PlainAnswer = traced("plain", question) { trace ->
        val (prompt, promptDuration) = measureTimedValue { Prompts.plain(question) }
        trace += PromptStep.of(prompt, question, promptDuration)
        val completion = llm.complete(prompt)
        trace += LlmStep.of(completion)
        PlainAnswer(question, completion.text, prompt, LlmCall.of(completion), trace.id)
    }

    /** С RAG: вопрос → поиск релевантных чанков → объединение с вопросом → запрос к LLM. */
    fun answerWithRag(question: String): RagAnswer = traced("rag", question) { trace ->
        val retrieval = retriever.search(question)
        trace += EmbeddingStep.of(embeddings.model, retrieval.query)
        trace += SearchStep.of(retrieval)
        val (prompt, promptDuration) = measureTimedValue { Prompts.withContext(question, retrieval.chunks) }
        trace += PromptStep.of(prompt, question, promptDuration)
        val completion = llm.complete(prompt)
        trace += LlmStep.of(completion)
        RagAnswer(
            question = question,
            answer = completion.text,
            prompt = prompt,
            llm = LlmCall.of(completion),
            traceId = trace.id,
            sources = retrieval.chunks.mapIndexed { i, found -> Source.of(i + 1, found) },
            retrievalMs = retrieval.query.exchange.durationMs + retrieval.searchDuration.inWholeMilliseconds,
        )
    }

    /** Записывает трассировку и когда ответ получен, и когда шаг упал: видно, на каком именно. */
    private fun <T : AgentAnswer> traced(mode: String, question: String, block: (TraceRecorder) -> T): T {
        val trace = TraceRecorder(mode, question)
        try {
            val answer = block(trace)
            journal.save(trace.answered(answer.answer))
            return answer
        } catch (e: RuntimeException) {
            journal.save(trace.failed(e.message ?: e.javaClass.simpleName))
            throw e
        }
    }
}

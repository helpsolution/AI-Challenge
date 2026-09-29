package advent.rag.agent

import advent.rag.knowledge.ScoredChunk
import advent.rag.llm.ChatMessage
import advent.rag.llm.Completion

/** Ответ агента. У режимов свои модели: у ответа без RAG нет ни источников, ни поиска. */
sealed interface AgentAnswer {
    val mode: String
    val question: String
    val answer: String

    /** Сообщения, которые ушли в модель: видно, во что превратился вопрос. */
    val prompt: List<ChatMessage>
    val llm: LlmCall

    /** По нему страница «Внутри агента» показывает все шаги этого ответа. */
    val traceId: String
}

/** Ответ без RAG: модель опирается только на то, что запомнила при обучении. */
data class PlainAnswer(
    override val question: String,
    override val answer: String,
    override val prompt: List<ChatMessage>,
    override val llm: LlmCall,
    override val traceId: String,
) : AgentAnswer {
    override val mode = "plain"
}

/** Ответ с RAG: модель опирается на найденные фрагменты базы знаний. */
data class RagAnswer(
    override val question: String,
    override val answer: String,
    override val prompt: List<ChatMessage>,
    override val llm: LlmCall,
    override val traceId: String,
    /** Фрагменты под номерами из промпта: [1] — самый похожий на вопрос. */
    val sources: List<Source>,
    /** Эмбеддинг вопроса и поиск по базе, мс. */
    val retrievalMs: Long,
) : AgentAnswer {
    override val mode = "rag"
}

/** Фрагмент базы знаний, который ушёл в промпт под номером [number]. */
data class Source(
    val number: Int,
    val chunkId: String,
    val source: String,
    val title: String,
    val url: String?,
    /** Косинусная близость к вопросу. */
    val score: Double,
    val text: String,
) {
    companion object {
        fun of(number: Int, found: ScoredChunk) = Source(
            number = number,
            chunkId = found.chunk.chunkId,
            source = found.chunk.source,
            title = found.chunk.title,
            url = found.chunk.url,
            score = found.score,
            text = found.chunk.text,
        )
    }
}

/** Цена запроса к модели. Токены промпта с RAG показывают, сколько стоит контекст. */
data class LlmCall(
    val model: String,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val durationMs: Long,
) {
    companion object {
        fun of(completion: Completion) = LlmCall(
            model = completion.model,
            promptTokens = completion.promptTokens,
            completionTokens = completion.completionTokens,
            durationMs = completion.exchange.durationMs,
        )
    }
}

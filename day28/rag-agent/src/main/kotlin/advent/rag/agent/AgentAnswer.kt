package advent.rag.agent

import advent.rag.grounding.Quote
import advent.rag.llm.ChatMessage
import advent.rag.llm.ChatModel
import advent.rag.llm.Completion
import advent.rag.llm.Provider
import advent.rag.llm.Timings
import advent.rag.reranking.RerankedChunk
import advent.rag.retrieval.Retrieval
import advent.rag.rewrite.RewriteInfo
import com.fasterxml.jackson.annotation.JsonValue

// answered — ответ с подтверждёнными цитатами; три следующих — виды «не знаю»; failed — модель не ответила вовсе.
enum class AnswerStatus(@get:JsonValue val code: String) {
    ANSWERED("answered"),
    WEAK_CONTEXT("weak_context"),
    NO_ANSWER("no_answer"),
    UNVERIFIED("unverified"),
    FAILED("failed"),
}

// Общая часть ответа: поиск выполняется один раз, и все модели получают один и тот же промпт.
data class RetrievalResult(
    val question: String,
    val rewriting: RewriteInfo,
    val relevance: Relevance,
    // Фрагменты, прошедшие порог, — ровно то, что ушло в промпт под номерами [n].
    val fragments: List<Source>,
    // Пустой промпт: ни один фрагмент не прошёл порог, модели не вызываются.
    val prompt: List<ChatMessage>,
    val retrievalMs: Long,
    val models: List<ModelRef>,
    val warnings: List<String>,
    val traceId: String,
)

data class ModelRef(val provider: Provider, val model: String) {
    companion object {
        fun of(model: ChatModel) = ModelRef(model.provider, model.model)
    }
}

data class ModelAnswer(
    val provider: Provider,
    val model: String,
    val status: AnswerStatus,
    val answer: String,
    val reason: String,
    // Только фрагменты, из которых есть подтверждённая цитата; у «не знаю» оба списка пусты.
    val sources: List<Source>,
    val quotes: List<Quote>,
    // null — модель не вызывалась (слабый контекст) или не ответила (failed).
    val llm: LlmCall?,
    // Текст, который модель написала, но проверка цитат отклонила: по нему видно, где именно она ошиблась.
    val draft: String?,
)

data class Relevance(
    val model: String, val candidateK: Int, val topK: Int, val minScore: Double,
    val bestScore: Double, val passed: Int, val durationMs: Long,
) {
    companion object {
        fun of(retrieval: Retrieval) = Relevance(retrieval.reranking.model, retrieval.candidateK, retrieval.topK,
            retrieval.minScore, retrieval.top.first().rerankScore, retrieval.context.size, retrieval.reranking.exchange.durationMs)
    }
}

data class Source(
    val number: Int, val chunkId: String, val source: String, val title: String,
    val url: String?, val section: String?, val score: Double, val rerankScore: Double, val text: String,
) {
    companion object {
        fun of(number: Int, found: RerankedChunk) = Source(number, found.chunk.chunkId, found.chunk.source,
            found.chunk.title, found.chunk.url, found.chunk.section, found.score, found.rerankScore, found.chunk.content)
    }
}

// durationMs — полное время HTTP-запроса к модели; timings — его разбивка от Ollama, у облака null.
data class LlmCall(
    val model: String, val promptTokens: Int?, val completionTokens: Int?, val finishReason: String?,
    val durationMs: Long, val timings: Timings?,
) {
    companion object {
        fun of(completion: Completion) = LlmCall(completion.model, completion.promptTokens, completion.completionTokens,
            completion.finishReason, completion.exchange.durationMs, completion.timings)
    }
}

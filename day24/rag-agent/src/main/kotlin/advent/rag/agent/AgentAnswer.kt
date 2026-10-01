package advent.rag.agent

import advent.rag.grounding.Quote
import advent.rag.llm.ChatMessage
import advent.rag.llm.Completion
import advent.rag.reranking.RerankedChunk
import advent.rag.retrieval.Retrieval
import advent.rag.rewrite.RewriteInfo
import com.fasterxml.jackson.annotation.JsonValue

// Всё, кроме answered, — «не знаю»; статус говорит, на каком шаге агент отказался отвечать.
enum class AnswerStatus(@get:JsonValue val code: String) {
    ANSWERED("answered"),
    WEAK_CONTEXT("weak_context"),
    NO_ANSWER("no_answer"),
    UNVERIFIED("unverified"),
}

data class AgentAnswer(
    val question: String,
    val status: AnswerStatus,
    val answer: String,
    val reason: String,
    // Только фрагменты, из которых есть подтверждённая цитата; у «не знаю» оба списка пусты.
    val sources: List<Source>,
    val quotes: List<Quote>,
    val relevance: Relevance,
    // Пустой промпт и llm = null: модель не вызывалась, потому что контекст слабее порога.
    val prompt: List<ChatMessage>,
    val llm: LlmCall?,
    val traceId: String,
    val retrievalMs: Long,
    val rewriting: RewriteInfo,
    val warnings: List<String>,
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

data class LlmCall(val model: String, val promptTokens: Int?, val completionTokens: Int?, val durationMs: Long) {
    companion object {
        fun of(completion: Completion) = LlmCall(completion.model, completion.promptTokens, completion.completionTokens, completion.exchange.durationMs)
    }
}

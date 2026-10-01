package advent.rag.trace

import advent.rag.embedding.QueryEmbedding
import advent.rag.agent.RouteDecision
import advent.rag.http.HttpExchange
import advent.rag.llm.ChatMessage
import advent.rag.llm.Completion
import advent.rag.retrieval.Retrieval
import advent.rag.rewrite.RewriteInfo
import kotlin.math.sqrt
import kotlin.time.Duration

sealed interface TraceStep {
    val kind: String
    val durationMs: Double
}

data class RouteStep(val decision: RouteDecision, val exchange: HttpExchange?) : TraceStep {
    override val kind = "route"
    override val durationMs = decision.durationMs.toDouble()
}

data class RewriteStep(val decision: RewriteInfo, val exchange: HttpExchange?) : TraceStep {
    override val kind = "rewrite"
    override val durationMs = decision.durationMs.toDouble()
}

data class EmbeddingStep(
    val model: String,
    val input: String,
    val dimensions: Int,
    val tokens: Int?,
    val norm: Double,
    val vector: FloatArray,
    val exchange: HttpExchange,
) : TraceStep {
    override val kind = "embedding"
    override val durationMs = exchange.durationMs.toDouble()

    companion object {
        fun of(model: String, query: QueryEmbedding) = EmbeddingStep(
            model = model,
            input = query.input,
            dimensions = query.vector.size,
            tokens = query.tokens,
            norm = sqrt(query.vector.sumOf { it.toDouble() * it }),
            vector = query.vector,
            exchange = query.exchange,
        )
    }
}

data class SearchStep(
    val chunksCompared: Int,
    val topK: Int,
    val scores: List<Double>,
    val candidates: List<Candidate>,
    override val durationMs: Double,
) : TraceStep {
    override val kind = "search"

    companion object {
        private const val RUNNERS_UP = 5

        fun of(retrieval: Retrieval) = SearchStep(
            chunksCompared = retrieval.ranking.size,
            topK = retrieval.candidateK,
            scores = retrieval.ranking.map { it.score },
            candidates = retrieval.ranking.take(retrieval.candidateK + RUNNERS_UP).mapIndexed { i, found ->
                Candidate(
                    rank = i + 1,
                    selected = i < retrieval.candidateK,
                    chunkId = found.chunk.chunkId,
                    title = found.chunk.title,
                    score = found.score,
                    text = found.chunk.content,
                )
            },
            durationMs = retrieval.searchDuration.toMillis(),
        )
    }
}

data class Candidate(
    val rank: Int,
    val selected: Boolean,
    val chunkId: String,
    val title: String,
    val score: Double,
    val text: String,
)

data class RerankStep(
    val enabled: Boolean,
    val model: String?,
    val candidateK: Int,
    val topK: Int,
    val candidates: List<RerankCandidate>,
    val exchange: HttpExchange?,
) : TraceStep {
    override val kind = "rerank"
    override val durationMs = exchange?.durationMs?.toDouble() ?: 0.0

    companion object {
        fun of(retrieval: Retrieval): RerankStep {
            val before = retrieval.ranking.mapIndexed { i, found -> found.chunk.chunkId to i + 1 }.toMap()
            return RerankStep(retrieval.reranking != null, retrieval.reranking?.model, retrieval.candidateK,
                retrieval.topK, (retrieval.reranking?.ranking ?: retrieval.chunks).mapIndexed { i, found ->
                    RerankCandidate(i + 1, before.getValue(found.chunk.chunkId), i < retrieval.topK,
                        found.chunk.chunkId, found.chunk.title, found.chunk.section, found.score, found.rerankScore, found.chunk.content)
                }, retrieval.reranking?.exchange)
        }
    }
}

data class RerankCandidate(
    val rank: Int, val originalRank: Int, val selected: Boolean, val chunkId: String,
    val title: String, val section: String?, val score: Double, val rerankScore: Double?, val text: String,
)

data class PromptStep(
    val messages: List<ChatMessage>,
    val parts: List<PromptPart>,
    override val durationMs: Double,
) : TraceStep {
    override val kind = "prompt"

    companion object {
        fun of(messages: List<ChatMessage>, question: String, duration: Duration): PromptStep {
            val rules = messages.filter { it.role == "system" }.sumOf { it.content.length }
            val user = messages.filter { it.role != "system" }.sumOf { it.content.length }
            val context = user - question.length
            return PromptStep(
                messages = messages,
                parts = listOfNotNull(
                    PromptPart("Правила", rules),
                    if (context > 0) PromptPart("Контекст и история", context) else null,
                    PromptPart("Вопрос", question.length),
                ),
                durationMs = duration.toMillis(),
            )
        }
    }
}

data class PromptPart(val label: String, val chars: Int)

data class LlmStep(
    val model: String,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val finishReason: String?,
    val exchange: HttpExchange,
) : TraceStep {
    override val kind = "llm"
    override val durationMs = exchange.durationMs.toDouble()

    companion object {
        fun of(completion: Completion) = LlmStep(
            model = completion.model,
            promptTokens = completion.promptTokens,
            completionTokens = completion.completionTokens,
            finishReason = completion.finishReason,
            exchange = completion.exchange,
        )
    }
}

private fun Duration.toMillis(): Double = inWholeMicroseconds / 1000.0

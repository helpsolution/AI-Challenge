package advent.rag.retrieval

import advent.rag.embedding.EmbeddingClient
import advent.rag.embedding.QueryEmbedding
import advent.rag.knowledge.KnowledgeBase
import advent.rag.knowledge.ScoredChunk
import advent.rag.reranking.RerankedChunk
import advent.rag.reranking.RerankerClient
import advent.rag.reranking.Reranking
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import kotlin.time.Duration
import kotlin.time.measureTimedValue

@ConfigurationProperties("retrieval")
data class RetrievalProperties(val topK: Int, val candidateK: Int, val minRerankScore: Double) {
    init { require(topK in 1..candidateK) { "Нужно 1 ≤ top-K ≤ candidate-K" } }
}

data class Retrieval(
    val query: QueryEmbedding,
    val ranking: List<ScoredChunk>,
    val searchDuration: Duration,
    val candidateK: Int,
    val reranking: Reranking,
    val topK: Int,
    val minScore: Double,
) {
    val top: List<RerankedChunk> get() = reranking.ranking.take(topK)

    // Рейтинг отсортирован по оценке BGE, поэтому прошедшие порог — это начало top-K и номера [n] не сдвигаются.
    val context: List<RerankedChunk> get() = top.filter { it.rerankScore >= minScore }
    val durationMs: Long get() = query.exchange.durationMs + searchDuration.inWholeMilliseconds + reranking.exchange.durationMs
}

@Component
class Retriever(
    private val embeddings: EmbeddingClient,
    private val knowledge: KnowledgeBase,
    private val properties: RetrievalProperties,
    private val reranker: RerankerClient,
) {
    fun search(question: String): Retrieval {
        val query = embeddings.embedQuery(question)
        val (ranking, duration) = measureTimedValue { knowledge.rank(query.vector) }
        val candidateK = properties.candidateK.coerceAtMost(ranking.size)
        val reranking = reranker.rerank(question, ranking.take(candidateK))
        return Retrieval(query, ranking, duration, candidateK, reranking, properties.topK.coerceAtMost(candidateK),
            properties.minRerankScore)
    }
}

package advent.rag.retrieval

import advent.rag.embedding.EmbeddingClient
import advent.rag.embedding.QueryEmbedding
import advent.rag.knowledge.KnowledgeBase
import advent.rag.knowledge.ScoredChunk
import advent.rag.reranking.RerankerClient
import advent.rag.reranking.RerankerProperties
import advent.rag.reranking.Reranking
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import kotlin.time.Duration
import kotlin.time.measureTimedValue

@ConfigurationProperties("retrieval")
data class RetrievalProperties(val topK: Int, val candidateK: Int) {
    init { require(topK in 1..50 && candidateK in topK..100) { "Нужно 1 ≤ top-K ≤ candidate-K ≤ 100, top-K ≤ 50" } }
}

data class Retrieval(
    val query: QueryEmbedding,
    val ranking: List<ScoredChunk>,
    val topK: Int,
    val searchDuration: Duration,
    val candidateK: Int,
    val reranking: Reranking? = null,
) {
    val chunks: List<ScoredChunk> get() = (reranking?.ranking ?: ranking).take(topK)
    val durationMs: Long get() = query.exchange.durationMs + searchDuration.inWholeMilliseconds + (reranking?.exchange?.durationMs ?: 0)
}

@Component
class Retriever(
    private val embeddings: EmbeddingClient,
    private val knowledge: KnowledgeBase,
    private val properties: RetrievalProperties,
    private val reranker: RerankerClient,
    private val rerankerProperties: RerankerProperties,
) {
    val rerankEnabled: Boolean get() = rerankerProperties.enabled

    fun search(question: String, rerank: Boolean): Retrieval {
        val query = embeddings.embedQuery(question)
        val (ranking, duration) = measureTimedValue { knowledge.rank(query.vector) }
        return Retrieval(query, ranking, properties.topK.coerceAtMost(ranking.size), duration,
            (if (rerank) properties.candidateK else properties.topK).coerceAtMost(ranking.size))
    }

    fun rerank(question: String, retrieval: Retrieval): Retrieval =
        retrieval.copy(reranking = reranker.rerank(question, retrieval.ranking.take(retrieval.candidateK)))
}

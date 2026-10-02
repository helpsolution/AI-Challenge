package advent.rag.retrieval

import advent.rag.embedding.EmbeddingClient
import advent.rag.embedding.QueryEmbedding
import advent.rag.knowledge.KnowledgeBase
import advent.rag.knowledge.ScoredChunk
import advent.rag.reranking.RerankerClient
import advent.rag.reranking.Reranking
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import kotlin.time.Duration
import kotlin.time.measureTimedValue

@ConfigurationProperties("retrieval")
data class RetrievalProperties(val topK: Int, val candidateK: Int) {
    init { require(topK in 1..50 && candidateK in topK..100) { "Нужно 1 ≤ top-K ≤ candidate-K ≤ 100, top-K ≤ 50" } }
}

/** Все чанки по косинусу; первые candidateK уходят в реранкер. */
data class Search(
    val query: QueryEmbedding,
    val ranking: List<ScoredChunk>,
    val candidateK: Int,
    val topK: Int,
    val duration: Duration,
) {
    val candidates: List<ScoredChunk> get() = ranking.take(candidateK)
}

data class Retrieval(val search: Search, val reranking: Reranking) {
    val chunks: List<ScoredChunk> get() = reranking.ranking.take(search.topK)
    val durationMs: Long get() = search.query.exchange.durationMs + search.duration.inWholeMilliseconds + reranking.exchange.durationMs
}

@Component
class Retriever(
    private val embeddings: EmbeddingClient,
    private val knowledge: KnowledgeBase,
    private val properties: RetrievalProperties,
    private val reranker: RerankerClient,
) {
    fun search(question: String): Search {
        val query = embeddings.embedQuery(question)
        val (ranking, duration) = measureTimedValue { knowledge.rank(query.vector) }
        return Search(query, ranking, properties.candidateK.coerceAtMost(ranking.size),
            properties.topK.coerceAtMost(ranking.size), duration)
    }

    fun rerank(question: String, search: Search): Retrieval = Retrieval(search, reranker.rerank(question, search.candidates))
}

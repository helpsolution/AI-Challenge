package advent.rag.agent

import advent.rag.embedding.EmbeddingClient
import advent.rag.knowledge.KnowledgeBase
import advent.rag.reranking.RerankedChunk
import advent.rag.reranking.RerankerClient
import advent.rag.trace.Recorder
import org.springframework.stereotype.Component

@Component
class ProjectRetriever(private val knowledge: KnowledgeBase, private val embeddings: EmbeddingClient,
    private val reranker: RerankerClient) {
    fun search(query: String, scope: String, trace: Recorder): List<RerankedChunk> {
        val embedding = embeddings.embedQuery(query)
        trace.add("Эмбеддинг вопроса", "ok", embedding.exchange.durationMs, mapOf("model" to embeddings.model, "query" to query))
        val start = System.nanoTime()
        val candidates = knowledge.rank(embedding.vector)
            .filter { scope == "repo" || it.chunk.source.startsWith("$scope/") }.take(20)
        trace.add("Поиск в документации", "ok", (System.nanoTime() - start) / 1_000_000,
            mapOf("scope" to scope, "candidates" to candidates.map { mapOf("path" to it.chunk.source, "section" to it.chunk.section, "cosine" to it.score) }))
        if (candidates.isEmpty()) return emptyList()
        val reranked = reranker.rerank(query, candidates)
        val context = reranked.ranking.take(5).filter { it.rerankScore >= 0.0 }
        trace.add("Реранкинг и порог", "ok", reranked.exchange.durationMs,
            mapOf("model" to reranked.model, "threshold" to 0, "passed" to context.size,
                "ranking" to reranked.ranking.map { mapOf("path" to it.chunk.source, "section" to it.chunk.section, "score" to it.rerankScore) }))
        return context
    }
}

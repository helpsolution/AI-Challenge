package advent.support.knowledge

import advent.support.embedding.EmbeddingClient
import org.springframework.stereotype.Component

@Component
class DocsSearch(private val indexer: Indexer, private val knowledge: KnowledgeBase,
    private val embeddings: EmbeddingClient, private val properties: KnowledgeProperties) {
    // Корпус маленький (десяток страниц), поэтому хватает косинусной близости без реранкера.
    fun search(query: String): List<ScoredChunk> {
        indexer.ensureFresh()
        return knowledge.rank(embeddings.embedQuery(query)).take(properties.topK)
    }
}

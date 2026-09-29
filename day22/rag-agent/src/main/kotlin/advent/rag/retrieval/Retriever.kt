package advent.rag.retrieval

import advent.rag.embedding.EmbeddingClient
import advent.rag.embedding.QueryEmbedding
import advent.rag.knowledge.KnowledgeBase
import advent.rag.knowledge.ScoredChunk
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import kotlin.time.Duration
import kotlin.time.measureTimedValue

/** Настройки из блока retrieval в application.yml. */
@ConfigurationProperties("retrieval")
data class RetrievalProperties(val topK: Int)

/** Результат поиска: вектор вопроса, весь рейтинг чанков и сколько из них ушло в промпт. */
data class Retrieval(
    val query: QueryEmbedding,
    /** Все чанки базы, самые похожие первыми: по хвосту видно, что «почти попало». */
    val ranking: List<ScoredChunk>,
    val topK: Int,
    /** Только сравнение векторов, без эмбеддинга вопроса. */
    val searchDuration: Duration,
) {
    /** Фрагменты, которые уходят в промпт. */
    val chunks: List<ScoredChunk> get() = ranking.take(topK)
}

/**
 * Клиент RAG для агента: вопрос → вектор той же моделью, что и чанки, → top-K ближайших чанков базы знаний.
 * Агент не знает ни про эмбеддинги, ни про SQLite — только «дай фрагменты по вопросу».
 */
@Component
class Retriever(
    private val embeddings: EmbeddingClient,
    private val knowledge: KnowledgeBase,
    private val properties: RetrievalProperties,
) {
    fun search(question: String): Retrieval {
        val query = embeddings.embedQuery(question)
        val (ranking, duration) = measureTimedValue { knowledge.rank(query.vector) }
        return Retrieval(query, ranking, properties.topK, duration)
    }
}

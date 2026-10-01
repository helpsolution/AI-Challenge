package advent.rag.knowledge

import advent.rag.chunking.Chunk
import org.springframework.stereotype.Component
import kotlin.math.sqrt

data class IndexedChunk(val chunk: Chunk, val vector: FloatArray)

data class ScoredChunk(val chunk: Chunk, val score: Double, val rerankScore: Double? = null)

@Component
class KnowledgeBase(properties: KnowledgeProperties) {
    private val store = SqliteStore(properties.indexFile)

    private class Snapshot(val chunks: List<IndexedChunk>, val meta: Map<String, String>)

    // Одна ссылка не позволяет поиску увидеть чанки и metadata разных версий.
    @Volatile
    private var snapshot = Snapshot(emptyList(), emptyMap())

    val meta: Map<String, String> get() = snapshot.meta

    fun load(): Boolean {
        if (!store.exists()) return false
        val (chunks, meta) = store.read()
        validate(chunks)
        snapshot = Snapshot(chunks, meta)
        return true
    }

    fun clear() { snapshot = Snapshot(emptyList(), emptyMap()) }
    fun chunks(): List<Chunk> = snapshot.chunks.map { it.chunk }
    fun titles(): List<String> = snapshot.chunks.map { it.chunk.title }.distinct()

    @Synchronized
    fun replace(chunks: List<IndexedChunk>, meta: Map<String, String>) {
        validate(chunks)
        store.write(chunks, meta)
        snapshot = Snapshot(chunks, meta)
    }

    private fun validate(chunks: List<IndexedChunk>) {
        check(chunks.isNotEmpty()) { "Индекс пуст" }
        val dimensions = chunks.first().vector.size
        check(dimensions > 0 && chunks.all { it.vector.size == dimensions && it.vector.all(Float::isFinite) && it.vector.any { value -> value != 0f } }) { "В индексе несовместимые или некорректные векторы" }
    }

    fun rank(query: FloatArray): List<ScoredChunk> {
        val chunks = snapshot.chunks
        check(chunks.isNotEmpty()) { "База знаний пуста: соберите её — POST /api/knowledge/rebuild" }
        return chunks
            .map { ScoredChunk(it.chunk, cosine(query, it.vector)) }
            .sortedByDescending { it.score }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "Вектор вопроса длины ${a.size}, а в базе — ${b.size}: пересоберите базу" }
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            normA += a[i].toDouble() * a[i]
            normB += b[i].toDouble() * b[i]
        }
        require(normA > 0 && normB > 0) { "Нулевой вектор нельзя сравнить" }
        return (dot / (sqrt(normA) * sqrt(normB))).coerceIn(-1.0, 1.0)
    }
}

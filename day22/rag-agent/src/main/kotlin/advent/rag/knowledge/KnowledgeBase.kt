package advent.rag.knowledge

import advent.rag.chunking.FixedSizeChunk
import org.springframework.stereotype.Component
import kotlin.math.sqrt

/** Чанк базы знаний вместе с его вектором. */
data class IndexedChunk(val chunk: FixedSizeChunk, val vector: FloatArray)

/** Чанк, найденный по вопросу, и его косинусная близость к вопросу: от −1 до 1, чем больше, тем ближе. */
data class ScoredChunk(val chunk: FixedSizeChunk, val score: Double)

/**
 * Локальная база знаний: чанки с векторами лежат в SQLite и целиком держатся в памяти.
 * Поиск — полный перебор: на сотнях чанков он занимает доли миллисекунды, векторный индекс не нужен.
 */
@Component
class KnowledgeBase(properties: KnowledgeProperties) {
    private val store = SqliteStore(properties.indexFile)

    /** Чанки и метаданные меняются вместе, одной ссылкой: поиск не увидит новые чанки со старой meta. */
    private class Snapshot(val chunks: List<IndexedChunk>, val meta: Map<String, String>)

    @Volatile
    private var snapshot = Snapshot(emptyList(), emptyMap())

    /** Модель, размер нарезки, число документов и чанков, время сборки. Пусто — базы ещё нет. */
    val meta: Map<String, String> get() = snapshot.meta

    /** Поднимает базу из файла в память. false — файла ещё нет. */
    fun load(): Boolean {
        if (!store.exists()) return false
        snapshot = Snapshot(store.readChunks(), store.readMeta())
        return true
    }

    /** Заменяет базу целиком: сначала файл, потом память. */
    fun replace(chunks: List<IndexedChunk>, meta: Map<String, String>) {
        store.write(chunks, meta)
        snapshot = Snapshot(chunks, meta)
    }

    /** Все чанки базы, самые похожие на вопрос первыми. Сколько из них брать в промпт, решает вызывающий. */
    fun rank(query: FloatArray): List<ScoredChunk> {
        val chunks = snapshot.chunks
        check(chunks.isNotEmpty()) { "База знаний пуста: соберите её — POST /api/knowledge/rebuild" }
        return chunks
            .map { ScoredChunk(it.chunk, cosine(query, it.vector)) }
            .sortedByDescending { it.score }
    }

    /**
     * Ollama отдаёт векторы уже нормированными, и косинус равен скалярному произведению.
     * Норму всё равно учитываем: другая модель эмбеддингов может отдавать векторы любой длины.
     */
    private fun cosine(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "Вектор вопроса длины ${a.size}, а в базе — ${b.size}: пересоберите базу" }
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        return dot / (sqrt(normA) * sqrt(normB))
    }
}

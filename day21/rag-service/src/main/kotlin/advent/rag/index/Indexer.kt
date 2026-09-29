package advent.rag.index

import advent.rag.chunking.Chunker
import advent.rag.chunking.FixedSizeChunker
import advent.rag.chunking.StructuralChunker
import advent.rag.document.Document
import advent.rag.embedding.EmbeddingClient
import advent.rag.embedding.OllamaProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.time.Instant
import kotlin.time.TimeSource

/** Итог пересборки: по строке на стратегию — это и есть первое сравнение стратегий. */
data class RebuildReport(
    val documents: Int,
    val fixedSize: StrategyReport,
    val structural: StrategyReport,
)

data class StrategyReport(
    val file: String,
    val chunks: Int,
    val avgLength: Int,
    val minLength: Int,
    val maxLength: Int,
    /** Сколько токенов модель прочитала за всю стратегию. */
    val tokens: Int,
    val embeddingSeconds: Double,
)

/**
 * Пересобирает оба индекса: корпус → чанки каждой стратегии → эмбеддинги → две базы SQLite.
 * Сначала всё считается в памяти и только потом пишется: если Ollama упадёт посередине,
 * старые индексы останутся целыми.
 */
@Component
class Indexer(
    private val embeddings: EmbeddingClient,
    private val ollama: OllamaProperties,
    private val properties: IndexProperties,
    private val fixedSizeIndex: FixedSizeIndex,
    private val structuralIndex: StructuralIndex,
) {
    fun rebuild(chunkSize: Int, overlap: Int, maxChunkSize: Int): RebuildReport {
        val fixedSize = FixedSizeChunker(chunkSize, overlap)
        val structural = StructuralChunker(maxChunkSize)
        val documents = corpus()

        val fixedChunks = embed(documents, fixedSize) { it.text }
        val structuralChunks = embed(documents, structural) { it.text }

        fixedSizeIndex.write(fixedChunks.rows, meta(documents, fixedChunks, "chunk_size" to chunkSize, "overlap" to overlap))
        structuralIndex.write(structuralChunks.rows, meta(documents, structuralChunks, "max_chunk_size" to maxChunkSize))

        return RebuildReport(
            documents = documents.size,
            fixedSize = fixedChunks.report(fixedSizeIndex.path.toString()) { it.text },
            structural = structuralChunks.report(structuralIndex.path.toString()) { it.text },
        )
    }

    private fun corpus(): List<Document> {
        val dir = properties.corpusDir
        require(Files.isDirectory(dir)) { "Нет корпуса $dir: положите туда документы .md" }
        val documents = Files.list(dir).use { files ->
            files.filter { it.toString().endsWith(".md") }
                .sorted()
                .map { Document.parse(it.toString(), Files.readString(it)) }
                .toList()
        }
        require(documents.isNotEmpty()) { "В $dir нет файлов .md" }
        return documents
    }

    /** Один запрос к модели на документ: его чанки уходят пачкой, с префиксом задачи. */
    private fun <T> embed(documents: List<Document>, chunker: Chunker<T>, text: (T) -> String): Embedded<T> {
        val started = TimeSource.Monotonic.markNow()
        var tokens = 0
        val rows = documents.flatMap { document ->
            val chunks = chunker.split(document)
            if (chunks.isEmpty()) return@flatMap emptyList()
            val result = embeddings.embed(chunks.map { ollama.documentPrefix + text(it) })
            tokens += result.tokens ?: 0
            chunks.zip(result.vectors)
        }
        val seconds = started.elapsedNow().inWholeMilliseconds / 1000.0
        log.info("{}: {} чанков, {} токенов, {} с", chunker::class.simpleName, rows.size, tokens, seconds)
        return Embedded(rows, tokens, seconds)
    }

    /** Модель и префикс нужны поиску: вопрос кодируют той же моделью, что и документы, иначе векторы не сравнить. */
    private fun meta(documents: List<Document>, embedded: Embedded<*>, vararg parameters: Pair<String, Int>) = mapOf(
        "model" to ollama.model,
        "dimensions" to embedded.rows.first().second.size,
        "document_prefix" to ollama.documentPrefix,
        "corpus" to properties.corpusDir,
        "documents" to documents.size,
        "chunks" to embedded.rows.size,
        "built_at" to Instant.now(),
    ) + parameters

    private class Embedded<T>(val rows: List<Pair<T, FloatArray>>, val tokens: Int, val seconds: Double) {
        fun report(file: String, text: (T) -> String): StrategyReport {
            val lengths = rows.map { text(it.first).length }
            return StrategyReport(
                file = file,
                chunks = rows.size,
                avgLength = lengths.average().toInt(),
                minLength = lengths.min(),
                maxLength = lengths.max(),
                tokens = tokens,
                embeddingSeconds = seconds,
            )
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(Indexer::class.java)
    }
}

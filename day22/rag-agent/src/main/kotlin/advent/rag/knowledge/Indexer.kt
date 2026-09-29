package advent.rag.knowledge

import advent.rag.chunking.Document
import advent.rag.chunking.FixedSizeChunker
import advent.rag.embedding.EmbeddingClient
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.time.Instant
import kotlin.time.measureTimedValue

/** Итог сборки базы знаний. */
data class RebuildReport(val documents: Int, val chunks: Int, val tokens: Int, val seconds: Double)

/**
 * Собирает базу знаний: корпус → чанки фиксированного размера → эмбеддинги → SQLite.
 * Всё считается в памяти и только потом пишется: упавшая посередине Ollama не портит старую базу.
 */
@Component
class Indexer(
    private val embeddings: EmbeddingClient,
    private val knowledge: KnowledgeBase,
    private val properties: KnowledgeProperties,
) {
    /**
     * При старте поднимает готовую базу, а если её нет — собирает. Базу другой модели эмбеддингов
     * тоже пересобирает: её векторы с векторами вопроса не сравнить. Выполняется до того, как веб-сервер
     * начнёт принимать запросы, иначе первые вопросы увидели бы пустую базу.
     */
    @PostConstruct
    fun loadOrBuild() {
        if (knowledge.load() && knowledge.meta["model"] == embeddings.model) {
            log.info("База знаний поднята из файла: чанков — {}, документов — {}", knowledge.meta["chunks"], knowledge.meta["documents"])
            return
        }
        // Без Ollama агент всё равно отвечает без RAG, поэтому сервис не падает, а ждёт ручной пересборки.
        runCatching { rebuild() }.onFailure { log.warn("База знаний не собрана: {}", it.message) }
    }

    fun rebuild(): RebuildReport {
        val chunker = FixedSizeChunker(properties.chunkSize, properties.overlap)
        val documents = corpus()
        var tokens = 0
        val (chunks, duration) = measureTimedValue {
            // Один запрос к модели на документ: его чанки уходят пачкой.
            documents.flatMap { document ->
                val chunks = chunker.split(document)
                if (chunks.isEmpty()) return@flatMap emptyList()
                val result = embeddings.embedDocuments(chunks.map { it.text })
                tokens += result.tokens ?: 0
                chunks.zip(result.vectors, ::IndexedChunk)
            }
        }
        knowledge.replace(
            chunks,
            mapOf(
                "model" to embeddings.model,
                "dimensions" to chunks.first().vector.size.toString(),
                "chunk_size" to properties.chunkSize.toString(),
                "overlap" to properties.overlap.toString(),
                "documents" to documents.size.toString(),
                "chunks" to chunks.size.toString(),
                "built_at" to Instant.now().toString(),
            ),
        )
        val report = RebuildReport(documents.size, chunks.size, tokens, duration.inWholeMilliseconds / 1000.0)
        log.info("База знаний собрана: {}", report)
        return report
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

    private companion object {
        val log = LoggerFactory.getLogger(Indexer::class.java)
    }
}

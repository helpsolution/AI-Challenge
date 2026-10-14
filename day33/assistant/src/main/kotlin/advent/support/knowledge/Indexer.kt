package advent.support.knowledge

import advent.support.chunking.Document
import advent.support.chunking.StructuralChunker
import advent.support.embedding.EmbeddingClient
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import kotlin.io.path.extension
import kotlin.io.path.name

data class RebuildReport(val documents: Int, val chunks: Int, val seconds: Double)

// Документация продукта: Markdown из knowledge.docs-dir, путь в индексе — имя файла.
@Component
class Indexer(private val embeddings: EmbeddingClient, private val knowledge: KnowledgeBase,
    private val properties: KnowledgeProperties) {
    @Volatile final var lastError: String? = null
        private set

    @PostConstruct fun load() {
        runCatching { ensureFresh() }.onFailure {
            lastError = it.message; knowledge.clear()
            LoggerFactory.getLogger(javaClass).warn("RAG не готов: {}", it.message)
        }
    }

    fun documents(): List<Document> = Files.list(properties.docsDir).use { files ->
        files.filter { it.extension == "md" }.sorted().map { Document.parse(it.name, Files.readString(it)) }.toList()
    }

    // Индекс пересобирается, только если изменились документы, нарезка или модель эмбеддингов.
    @Synchronized fun ensureFresh(force: Boolean = false): RebuildReport? {
        try {
            val docs = documents()
            require(docs.isNotEmpty()) { "Нет документации в ${properties.docsDir}" }
            val digest = MessageDigest.getInstance("SHA-256")
            (listOf(StructuralChunker.VERSION, properties.targetChars.toString(), properties.maxChars.toString(), embeddings.signature) +
                docs.flatMap { listOf(it.source, it.title, it.text) }).forEach {
                digest.update(it.toByteArray()); digest.update(0.toByte())
            }
            val fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
            if (!force && (knowledge.chunks().isNotEmpty() || knowledge.load()) && knowledge.meta["fingerprint"] == fingerprint) {
                lastError = null; return null
            }
            val started = System.nanoTime()
            val chunker = StructuralChunker(properties.targetChars, properties.maxChars)
            val chunks = docs.flatMap { chunker.split(it) }.chunked(16).flatMap { batch ->
                batch.zip(embeddings.embedDocuments(batch.map { it.embeddingText }), ::IndexedChunk)
            }
            knowledge.replace(chunks, mapOf("fingerprint" to fingerprint, "documents" to docs.size.toString(),
                "chunks" to chunks.size.toString(), "built_at" to Instant.now().toString(), "model" to embeddings.model))
            lastError = null
            return RebuildReport(docs.size, chunks.size, (System.nanoTime() - started) / 1e9)
        } catch (e: Exception) {
            lastError = e.message
            throw e
        }
    }
}

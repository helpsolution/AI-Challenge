package advent.rag.knowledge

import advent.rag.chunking.Document
import advent.rag.chunking.StructuralChunker
import advent.rag.embedding.EmbeddingClient
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import kotlin.time.measureTimedValue

data class RebuildReport(val documents: Int, val chunks: Int, val tokens: Int, val seconds: Double)

@Component
class Indexer(private val embeddings: EmbeddingClient, private val knowledge: KnowledgeBase, private val properties: KnowledgeProperties) {
    @Volatile final var lastError: String? = null
        private set

    @PostConstruct
    fun loadOrBuild() {
        runCatching {
            val documents = corpus()
            if (knowledge.load() && knowledge.meta["fingerprint"] == fingerprint(documents)) {
                log.info("Индекс поднят: {} чанков", knowledge.meta["chunks"])
            } else {
                knowledge.clear()
                rebuild()
            }
        }.onFailure { lastError = it.message; knowledge.clear(); log.warn("RAG не готов: {}", it.message) }
    }

    @Synchronized
    fun rebuild(): RebuildReport {
        try {
            val documents = corpus()
            val chunker = StructuralChunker(properties.targetChars, properties.maxChars)
            var tokens = 0
            val (chunks, duration) = measureTimedValue {
                documents.flatMap { document ->
                    chunker.split(document).chunked(16).flatMap { batch ->
                        val result = embeddings.embedDocuments(batch.map { it.embeddingText })
                        tokens += result.tokens ?: 0
                        batch.zip(result.vectors, ::IndexedChunk)
                    }
                }
            }
            require(chunks.isNotEmpty()) { "Документы корпуса не содержат текста" }
            knowledge.replace(chunks, mapOf(
                "model" to embeddings.model, "dimensions" to chunks.first().vector.size.toString(),
                "strategy" to StructuralChunker.VERSION, "target_chars" to properties.targetChars.toString(),
                "max_chars" to properties.maxChars.toString(), "documents" to documents.size.toString(),
                "chunks" to chunks.size.toString(), "fingerprint" to fingerprint(documents), "built_at" to Instant.now().toString()))
            lastError = null
            return RebuildReport(documents.size, chunks.size, tokens, duration.inWholeMilliseconds / 1000.0)
        } catch (e: RuntimeException) { lastError = e.message; throw e }
    }

    private fun corpus(): List<Document> {
        val dir = properties.corpusDir
        require(Files.isDirectory(dir)) { "Нет корпуса $dir: положите туда документы .md" }
        val documents = Files.walk(dir).use { files ->
            files.filter { Files.isRegularFile(it) && it.toString().endsWith(".md") }.sorted()
                .map { Document.parse(dir.relativize(it).toString().replace('\\', '/'), Files.readString(it)) }.toList()
        }
        require(documents.isNotEmpty()) { "В $dir нет файлов .md" }
        return documents
    }

    private fun fingerprint(documents: List<Document>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val settings = listOf(StructuralChunker.VERSION, properties.targetChars, properties.maxChars, embeddings.signature)
        val parts = settings.map { it.toString() } + documents.flatMap { listOf(it.source, it.title, it.url.orEmpty(), it.text) }
        parts.forEach { digest.update(it.toByteArray()); digest.update(0.toByte()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    companion object { private val log = LoggerFactory.getLogger(Indexer::class.java) }
}

package advent.rag.knowledge

import advent.rag.chunking.StructuralChunker
import advent.rag.embedding.EmbeddingClient
import advent.rag.project.RepoDocuments
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Instant

data class RebuildReport(val documents: Int, val chunks: Int, val seconds: Double)

@Component
class Indexer(private val documents: RepoDocuments, private val embeddings: EmbeddingClient,
    private val knowledge: KnowledgeBase, private val properties: KnowledgeProperties) {
    @Volatile final var lastError: String? = null
        private set

    @PostConstruct fun load() {
        runCatching { ensureFresh() }.onFailure {
            lastError = it.message; knowledge.clear()
            LoggerFactory.getLogger(javaClass).warn("RAG не готов: {}", it.message)
        }
    }

    @Synchronized fun ensureFresh(force: Boolean = false): RebuildReport? {
        try {
            val docs = documents.documents()
            require(docs.isNotEmpty()) { "Нет README или docs в PROJECT_ROOT" }
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
                batch.zip(embeddings.embedDocuments(batch.map { it.embeddingText }).vectors, ::IndexedChunk)
            }
            require(chunks.isNotEmpty()) { "Документация не содержит текста" }
            knowledge.replace(chunks, mapOf("fingerprint" to fingerprint, "documents" to docs.size.toString(),
                "chunks" to chunks.size.toString(), "built_at" to Instant.now().toString(), "model" to embeddings.model,
                "root" to documents.root.toString()))
            lastError = null
            return RebuildReport(docs.size, chunks.size, (System.nanoTime() - started) / 1e9)
        } catch (e: Exception) {
            lastError = e.message
            // Старый индекс остаётся на диске, но запрос с устаревшей документацией не выполняется.
            throw e
        }
    }
}

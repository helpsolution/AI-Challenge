package advent.rag.knowledge

import advent.rag.chunking.Chunk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SqliteStoreTest {
    @TempDir lateinit var temp: Path
    private fun item(id: String = "intro.md#0") = IndexedChunk(
        Chunk(id, "intro.md", "Документ", "https://example.com", "Раздел", "Шапка\n", 0, 10, 14, "Тело"), floatArrayOf(0.3f, -0.7f, 0.2f))

    @Test fun `sqlite round trip keeps source metadata and float vectors`() {
        val store = SqliteStore(temp.resolve("knowledge.db"))
        store.write(listOf(item()), mapOf("model" to "local", "fingerprint" to "first"))
        val (chunks, meta) = store.read()
        assertEquals(item().chunk, chunks.single().chunk)
        assertArrayEquals(item().vector, chunks.single().vector)
        assertEquals("first", meta["fingerprint"])
    }

    @Test fun `failed replacement rolls back both chunks and metadata`() {
        val store = SqliteStore(temp.resolve("knowledge.db"))
        store.write(listOf(item()), mapOf("fingerprint" to "old"))
        assertThrows(Exception::class.java) { store.write(listOf(item("duplicate"), item("duplicate")), mapOf("fingerprint" to "new")) }
        val (chunks, meta) = store.read()
        assertEquals("intro.md#0", chunks.single().chunk.chunkId)
        assertEquals("old", meta["fingerprint"])
    }
}

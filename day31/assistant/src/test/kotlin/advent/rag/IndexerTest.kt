package advent.rag

import advent.rag.embedding.*
import advent.rag.knowledge.*
import advent.rag.project.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.*
import java.nio.file.Files
import java.nio.file.Path

class IndexerTest {
    @TempDir lateinit var root: Path
    @Test fun `changed document refreshes index and unchanged corpus uses persisted snapshot`() {
        val (indexer, knowledge, embeddings) = setup()
        assertNotNull(indexer.ensureFresh())
        val original = knowledge.meta.getValue("fingerprint")
        assertNull(indexer.ensureFresh())
        Files.writeString(root.resolve("day31/README.md"), "# Project\n\nНовая документация: порт 9999.")
        assertNotNull(indexer.ensureFresh())
        assertNotEquals(original, knowledge.meta["fingerprint"])
        assertTrue(knowledge.chunks().any { "9999" in it.text })
        verify(embeddings, times(2)).embedDocuments(anyList())
    }
    @Test fun `refresh failure preserves stored index but stops request`() {
        val (indexer, knowledge, embeddings) = setup()
        indexer.ensureFresh()
        val previous = knowledge.meta["fingerprint"]
        Files.writeString(root.resolve("day31/README.md"), "# Changed\n\nНовая документация.")
        `when`(embeddings.embedDocuments(anyList())).thenThrow(EmbeddingException("Ollama unavailable"))
        assertThrows(EmbeddingException::class.java) { indexer.ensureFresh() }
        assertEquals(previous, knowledge.meta["fingerprint"])
        assertEquals("Ollama unavailable", indexer.lastError)
    }
    private fun setup(): Triple<Indexer, KnowledgeBase, EmbeddingClient> {
        Files.createDirectories(root.resolve("day31"))
        Files.writeString(root.resolve("day31/README.md"), "# Project\n\nДокументация: порт 8311.")
        val embeddings = mock(EmbeddingClient::class.java)
        `when`(embeddings.signature).thenReturn("test")
        `when`(embeddings.model).thenReturn("test")
        `when`(embeddings.embedDocuments(anyList())).thenAnswer {
            Embeddings(it.getArgument<List<String>>(0).map { floatArrayOf(1f, 2f, 3f) }, 10)
        }
        val properties = KnowledgeProperties(root.resolve("index.db"), 1400, 2200)
        val knowledge = KnowledgeBase(properties)
        return Triple(Indexer(RepoDocuments(ProjectProperties(root.toString(), "unused")), embeddings, knowledge, properties), knowledge, embeddings)
    }
}

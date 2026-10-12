package advent.rag

import advent.rag.project.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files

class RepoDocumentsTest {
    @TempDir lateinit var root: Path
    @Test fun `corpus includes README and recursive docs but excludes other materials`() {
        val files = listOf("README.md", "day30/README.md", "day30/docs/api.md", "day31/docs/nested/architecture.md",
            "day30/data/private.md", "day30/src/secrets.md", "day30/.env", "материалы/lecture.md", "day31/docs/.hidden.md")
        files.forEach { file -> root.resolve(file).also { Files.createDirectories(it.parent); Files.writeString(it, "# $file\n\nДокумент.") } }
        val corpus = RepoDocuments(ProjectProperties(root.toString(), "http://localhost/mcp"))
        assertEquals(setOf("README.md", "day30/README.md", "day30/docs/api.md", "day31/docs/nested/architecture.md"),
            corpus.documents().map { it.source }.toSet())
        assertEquals(listOf("day30", "day31"), corpus.scopes())
    }
    @Test fun `symlinks outside project are not indexed`() {
        val outside = Files.createTempDirectory("day31-outside")
        try {
            Files.writeString(outside.resolve("private.md"), "private")
            Files.createDirectories(root.resolve("day31/docs"))
            Files.createSymbolicLink(root.resolve("day31/docs/private.md"), outside.resolve("private.md"))
            assertTrue(RepoDocuments(ProjectProperties(root.toString(), "unused")).documents().isEmpty())
        } finally { Files.deleteIfExists(outside.resolve("private.md")); Files.deleteIfExists(outside) }
    }
}

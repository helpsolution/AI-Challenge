package advent.files.mcp

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ProjectFilesTest {
    @TempDir lateinit var root: Path
    lateinit var files: ProjectFiles
    @BeforeEach fun setup() {
        write(root.resolve("seed/README.md"), "# API\nOld description")
        write(root.resolve("seed/docs/rules.md"), "# Rules\nController calls Service\n")
        write(root.resolve("seed/src/Controller.kt"), "class Controller(val service: Service)\n")
        write(root.resolve("seed/.env"), "secret")
        files = ProjectFiles(root.resolve("seed"), root.resolve("runs"))
    }

    @Test fun `runs are isolated and search spans files without secrets`() {
        val one = files.createWorkspace(); val two = files.createWorkspace()
        assertEquals(3, one.files.size)
        assertEquals(2, files.search(one.workspaceId, "Controller").matches.size)
        val read = files.readFile(one.workspaceId, "README.md")
        files.writeFile(one.workspaceId, "README.md", "# Updated", read.sha256)
        assertEquals("# API\nOld description", files.readFile(two.workspaceId, "README.md").content)
        assertEquals("# API\nOld description", Files.readString(root.resolve("seed/README.md")))
        assertEquals(listOf("README.md"), files.changes(one.workspaceId).readFiles)
        assertTrue(files.changes(two.workspaceId).changes.isEmpty())
    }

    @Test fun `existing files require reading and current hash`() {
        val id = files.createWorkspace().workspaceId
        assertFailsWith<IllegalArgumentException> { files.writeFile(id, "README.md", "new", null) }
        val initial = files.readFile(id, "README.md")
        val updated = files.writeFile(id, "README.md", "new", initial.sha256)
        assertFailsWith<IllegalArgumentException> { files.writeFile(id, "README.md", "stale", initial.sha256) }
        files.writeFile(id, "README.md", "latest", updated.sha256)
        assertEquals("latest\n", files.readFile(id, "README.md").content)
    }

    @Test fun `paths source writes and other workspace identifiers are rejected`() {
        val id = files.createWorkspace().workspaceId
        for (path in listOf("../README.md", "/etc/passwd", ".env", "docs/../../README.md", "docs\\escape.md", "docs/.hidden.md")) {
            assertFailsWith<IllegalArgumentException>(path) { files.readFile(id, path) }
            assertFailsWith<IllegalArgumentException>(path) { files.writeFile(id, path, "bad", null) }
        }
        assertFailsWith<IllegalArgumentException> { files.readFile("../seed", "README.md") }
        assertFailsWith<IllegalArgumentException> { files.writeFile(id, "src/Controller.kt", "bad", null) }
        assertFailsWith<IllegalArgumentException> { files.writeFile(id, "docs/audit.md", "", null) }
        assertFailsWith<IllegalArgumentException> { files.writeFile(id, "docs/audit.md", "a\u0000b", null) }
        assertFailsWith<IllegalArgumentException> { files.writeFile(id, "docs/audit.md", "x".repeat(ProjectFiles.MAX_FILE_BYTES + 1), null) }
    }

    @Test fun `symbolic links cannot expose or overwrite files outside the workspace`() {
        val run = files.createWorkspace(); val dir = Path.of(run.directory)
        val outside = root.resolve("outside.md"); write(outside, "private")
        Files.createSymbolicLink(dir.resolve("docs/link.md"), outside)
        Files.createSymbolicLink(dir.resolve("docs/linked"), root)
        assertFailsWith<IllegalArgumentException> { files.readFile(run.workspaceId, "docs/link.md") }
        assertFailsWith<IllegalArgumentException> { files.writeFile(run.workspaceId, "docs/link.md", "bad", null) }
        assertFailsWith<IllegalArgumentException> { files.writeFile(run.workspaceId, "docs/linked/outside.md", "bad", null) }
        assertFalse(files.listFiles(run.workspaceId).any { "link" in it.path })
        assertEquals("private", Files.readString(outside))
    }

    @Test fun `downloaded diff applies exactly including new file and missing final newline`() {
        val run = files.createWorkspace()
        val before = files.readFile(run.workspaceId, "README.md")
        files.writeFile(run.workspaceId, "README.md", "# API\nNew description\n", before.sha256)
        files.writeFile(run.workspaceId, "docs/audit.md", "# Audit\nFinding\n", null)
        val changes = files.changes(run.workspaceId)
        val patch = root.resolve("changes.patch")
        Files.writeString(patch, changes.changes.joinToString("") { it.diff })
        val original = Path.of(run.directory).parent.resolve("original")
        for (args in listOf(listOf("--check"), emptyList())) {
            val process = ProcessBuilder(listOf("git", "apply") + args + patch.toString()).directory(original.toFile()).apply {
                environment()["GIT_CEILING_DIRECTORIES"] = root.toString()
            }.redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output)
        }
        changes.changes.forEach { assertEquals(it.content, Files.readString(original.resolve(it.path))) }
    }

    private fun write(path: Path, text: String) { Files.createDirectories(path.parent); Files.writeString(path, text) }
}

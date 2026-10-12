package advent.project.mcp

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.*

class ProjectRepositoryTest {
    @TempDir lateinit var root: Path
    @BeforeEach fun setup() {
        git("init", "-b", "main"); git("config", "user.name", "Day31 Test"); git("config", "user.email", "day31@example.test")
        write("day31/README.md", "Documentation"); write(".gitignore", ".env\ndata/\n")
        git("add", "."); git("commit", "-m", "initial")
    }
    @Test fun `branch is live and detached head is explicit`() {
        val repo = ProjectRepository(root)
        assertEquals("main", repo.branch()["branch"]?.jsonPrimitive?.content)
        git("switch", "-c", "feature/day31")
        assertEquals("feature/day31", repo.branch()["branch"]?.jsonPrimitive?.content)
        git("checkout", "--detach")
        assertEquals(JsonNull, repo.branch()["branch"])
        assertTrue(repo.branch()["detached"]!!.jsonPrimitive.boolean)
    }
    @Test fun `file scope excludes private files and keeps new public files`() {
        write("day31/docs/Новый файл.md", "new"); write("day31/.env", "secret"); write("day31/data/private.md", "secret")
        write("day30/README.md", "other")
        val data = ProjectRepository(root).files("day31", 300)
        assertEquals(setOf("day31/README.md", "day31/docs/Новый файл.md"), data["files"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertFailsWith<IllegalArgumentException> { ProjectRepository(root).files("../", 100) }
        assertFailsWith<IllegalArgumentException> { ProjectRepository(root).files("day31", 301) }
    }
    @Test fun `status preserves unicode and spaces and represents rename`() {
        write("day31/Новый файл.md", "new")
        git("mv", "day31/README.md", "day31/Прочитай меня.md")
        val changes = ProjectRepository(root).status()["changes"]!!.jsonArray.map { it.jsonObject }
        assertTrue(changes.any { it["path"]!!.jsonPrimitive.content == "day31/Новый файл.md" })
        val renamed = changes.first { it["path"]!!.jsonPrimitive.content == "day31/Прочитай меня.md" }
        assertEquals("day31/README.md", renamed["originalPath"]!!.jsonPrimitive.content)
    }
    private fun write(path: String, text: String) { root.resolve(path).also { Files.createDirectories(it.parent); Files.writeString(it, text) } }
    private fun git(vararg args: String) {
        val p = ProcessBuilder(listOf("git", "-C", root.toString()) + args).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { output }
    }
}

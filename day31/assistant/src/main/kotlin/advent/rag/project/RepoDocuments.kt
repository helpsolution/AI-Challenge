package advent.rag.project

import advent.rag.chunking.Document
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/** Индексируем только README и Markdown в docs; исходники и локальные секреты в корпус не попадают. */
@Component
class RepoDocuments(private val properties: ProjectProperties) {
    val root: Path get() = Path.of(properties.root).toRealPath()

    fun documents(): List<Document> {
        val root = root
        val paths = mutableListOf<Path>()
        root.resolve("README.md").takeIf(::regular)?.let(paths::add)
        addDocs(root.resolve("docs"), paths)
        Files.list(root).use { children ->
            children.filter { Files.isDirectory(it) && DAY.matches(it.fileName.toString()) && it.toRealPath().startsWith(root) }
                .forEach { day ->
                    day.resolve("README.md").takeIf(::regular)?.let(paths::add)
                    addDocs(day.resolve("docs"), paths)
                }
        }
        return paths.distinct().sorted().map { path ->
            val relative = root.relativize(path).toString().replace('\\', '/')
            Document.parse(relative, Files.readString(path)).let { document ->
                val heading = document.text.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")
                document.copy(title = "$relative — ${heading ?: document.title}")
            }
        }
    }

    fun scopes(): List<String> = documents().map { it.source.substringBefore('/') }
        .filter(DAY::matches).distinct().sortedBy { it.removePrefix("day").toInt() }

    private fun regular(path: Path): Boolean = Files.isRegularFile(path) && path.toRealPath().startsWith(root)
    private fun addDocs(dir: Path, paths: MutableList<Path>) {
        if (!Files.isDirectory(dir) || !dir.toRealPath().startsWith(root)) return
        Files.walk(dir).use { files ->
            files.filter { regular(it) && it.toString().endsWith(".md") &&
                dir.relativize(it).none { part -> part.toString().startsWith('.') } }.forEach(paths::add)
        }
    }
    companion object { private val DAY = Regex("day[1-9]\\d*") }
}

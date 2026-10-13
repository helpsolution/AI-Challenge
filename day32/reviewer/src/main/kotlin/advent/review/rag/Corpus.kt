package advent.review.rag

import advent.review.chunking.Document
import advent.review.review.ReviewProperties
import org.springframework.stereotype.Component
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path

data class SourceFile(val path: String, val text: String)

/** Репозиторий в версии PR: изменённые файлы, документация и код затронутых папок, правила ревью. */
@Component
class Corpus(private val properties: ReviewProperties) {
    val root: Path get() = properties.root.toRealPath()

    // Папка верхнего уровня — отдельное приложение (dayN, agents); корневые файлы и .github своей области не дают.
    fun scopes(paths: List<String>): List<String> = paths.filter { '/' in it }.map { it.substringBefore('/') }
        .filter { !it.startsWith('.') && Files.isDirectory(root.resolve(it)) }.distinct().sorted()

    // Модуль — ближайшая к файлу папка со своим build.gradle.kts: код соседних модулей того же дня (например,
    // самого ревьюера рядом с песочницей) к изменению отношения не имеет и только зашумляет контекст.
    fun modules(paths: List<String>): List<String> {
        val modules = paths.filter { '/' in it && !it.startsWith('.') }.map { path ->
            val parts = path.split('/').dropLast(1)
            (parts.size downTo 1).map { parts.take(it).joinToString("/") }
                .firstOrNull { Files.isRegularFile(root.resolve(it).resolve("build.gradle.kts")) } ?: parts.first()
        }.distinct().filter { Files.isDirectory(root.resolve(it)) }
        // Вложенный модуль не нужен, если в списке уже есть объемлющий: его код и так попадёт в обход.
        return modules.filter { module -> modules.none { module.startsWith("$it/") } }
    }

    fun rules(): Document? = read(properties.rules)?.let { document(properties.rules, it) }

    fun documents(scopes: List<String>): List<Document> = files(scopes) { it.endsWith(".md") }
        .filter { it.path != properties.rules }.map { document(it.path, it.text) }

    fun code(scopes: List<String>): List<SourceFile> = files(scopes) { it.substringAfterLast('.') in CODE }

    fun read(path: String): String? {
        val file = root.resolve(path).normalize()
        if (!file.startsWith(root) || !Files.isRegularFile(file)) return null
        return try { Files.readString(file) } catch (e: CharacterCodingException) { null }
    }

    private fun files(scopes: List<String>, accept: (String) -> Boolean): List<SourceFile> = scopes.flatMap { scope ->
        val dir = root.resolve(scope)
        Files.walk(dir).use { paths ->
            paths.filter { Files.isRegularFile(it) && Files.size(it) <= MAX_FILE_BYTES }
                .map { root.relativize(it).toString().replace('\\', '/') }
                .filter { path -> path.split('/').dropLast(1).none { it.startsWith('.') || it in SKIP } && accept(path) }
                .toList()
        }
    }.distinct().sorted().mapNotNull { path -> read(path)?.let { SourceFile(path, it) } }

    private fun document(path: String, text: String): Document {
        val heading = text.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
        return Document(path, heading?.let { "$path — $it" } ?: path, null, text)
    }

    private companion object {
        val CODE = setOf("kt", "kts", "java", "py", "js", "ts", "vue", "html", "css", "yml", "yaml", "sql", "sh", "properties")
        val SKIP = setOf("build", "data", "gradle", "node_modules", "out", "__pycache__")
        // Больше — почти наверняка вендорный бандл (vue.global.js, system.css), он не помогает ревью.
        const val MAX_FILE_BYTES = 200_000L
    }
}

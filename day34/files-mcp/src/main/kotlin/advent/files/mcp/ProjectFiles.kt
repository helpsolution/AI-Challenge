package advent.files.mcp

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable data class FileEntry(val path: String, val bytes: Long)
@Serializable data class WorkspaceInfo(val workspaceId: String, val directory: String, val files: List<FileEntry>)
@Serializable data class FileText(val path: String, val sha256: String, val content: String, val numbered: String)
@Serializable data class Match(val path: String, val line: Int, val text: String)
@Serializable data class SearchResult(val matches: List<Match>, val truncated: Boolean)
@Serializable data class FileChange(val path: String, val kind: String, val content: String, val diff: String)
@Serializable data class ChangeSet(val workspaceId: String, val directory: String, val readFiles: List<String>, val changes: List<FileChange>)

/** Каждый запуск получает свою исходную копию и рабочую папку. Исходный пример не изменяется. */
class ProjectFiles(seed: Path, storage: Path) {
    private val seed = seed.toAbsolutePath().normalize()
    private val storage = storage.toAbsolutePath().normalize()
    private val reads = ConcurrentHashMap<String, MutableSet<String>>()
    private val locks = ConcurrentHashMap<String, Any>()

    init {
        require(Files.isDirectory(this.seed, NOFOLLOW_LINKS)) { "Не найдена папка примера: ${this.seed}" }
        require(!this.storage.startsWith(this.seed)) { "Рабочие копии должны находиться вне исходного примера" }
        Files.createDirectories(this.storage)
        require(!Files.isSymbolicLink(this.storage)) { "Папка рабочих копий не должна быть ссылкой" }
    }

    fun createWorkspace(): WorkspaceInfo {
        val id = UUID.randomUUID().toString()
        val base = storage.resolve(id)
        Files.createDirectory(base)
        val entries = entries(seed)
        require(entries.isNotEmpty()) { "В примере нет текстовых файлов" }
        for (folder in listOf("original", "project")) {
            val dest = Files.createDirectory(base.resolve(folder))
            entries.forEach { entry ->
                val source = checked(seed, entry.path)
                require(Files.size(source) <= MAX_FILE_BYTES) { "Слишком большой файл: ${entry.path}" }
                val target = dest.resolve(entry.path)
                Files.createDirectories(target.parent)
                Files.copy(source, target)
            }
        }
        reads[id] = ConcurrentHashMap.newKeySet()
        return WorkspaceInfo(id, base.resolve("project").toString(), entries)
    }

    fun listFiles(id: String): List<FileEntry> = locked(id) { entries(it.resolve("project")) }

    fun readFile(id: String, path: String): FileText = locked(id) { base ->
        val file = checked(base.resolve("project"), path)
        val content = read(file)
        reads.getOrPut(id) { ConcurrentHashMap.newKeySet() }.add(path)
        FileText(path, hash(content), content, content.lineSequence().mapIndexed { i, line -> "${i + 1}: $line" }.joinToString("\n"))
    }

    fun search(id: String, query: String): SearchResult = locked(id) { base ->
        require(query.isNotBlank() && query.length <= 300) { "query должен содержать 1–300 символов" }
        val root = base.resolve("project")
        val found = entries(root).flatMap { entry ->
            read(checked(root, entry.path)).lineSequence().mapIndexedNotNull { i, line ->
                if (line.contains(query, ignoreCase = true)) Match(entry.path, i + 1, line) else null
            }.toList()
        }
        SearchResult(found.take(120), found.size > 120)
    }

    fun writeFile(id: String, path: String, content: String, expectedHash: String?): FileText = locked(id) { base ->
        require(path == "README.md" || (path.startsWith("docs/") && path.endsWith(".md"))) {
            "Сохранять можно README.md и Markdown в docs/. Исходный код доступен для анализа."
        }
        val file = checked(base.resolve("project"), path)
        require(content.isNotBlank()) { "Пустой файл не сохраняется" }
        require('\u0000' !in content) { "Бинарные файлы не поддерживаются" }
        val normalized = content.replace("\r\n", "\n").let { if (it.endsWith("\n")) it else "$it\n" }
        require(normalized.toByteArray().size <= MAX_FILE_BYTES) { "Файл больше $MAX_FILE_BYTES байт" }
        if (Files.exists(file, NOFOLLOW_LINKS)) {
            require(path in reads[id].orEmpty()) { "Перед изменением прочитай $path через read_file" }
            require(expectedHash == hash(read(file))) { "Файл изменился или expected_hash не задан. Прочитай файл заново." }
        } else require(expectedHash.isNullOrBlank()) { "Новый файл: expected_hash должен отсутствовать" }
        Files.createDirectories(file.parent)
        // Повторная проверка после создания каталогов: ссылки за пределы рабочей копии запрещены.
        checked(base.resolve("project"), path)
        Files.writeString(file, normalized)
        FileText(path, hash(normalized), normalized, "")
    }

    fun changes(id: String): ChangeSet = locked(id) { base ->
        val original = base.resolve("original")
        val project = base.resolve("project")
        val changes = entries(project).mapNotNull { entry ->
            val after = checked(project, entry.path)
            val before = checked(original, entry.path)
            val text = read(after)
            if (Files.exists(before) && read(before) == text) null
            else FileChange(entry.path, if (Files.exists(before)) "modified" else "created", text, diff(entry.path, before, after))
        }
        ChangeSet(id, project.toString(), reads[id].orEmpty().sorted(), changes)
    }

    private fun diff(path: String, before: Path, after: Path): String {
        val exists = Files.exists(before)
        // Аргументы передаются напрямую: никаких shell-команд и настроек git из анализируемого проекта.
        val process = ProcessBuilder("git", "--no-pager", "diff", "--no-index", "--no-ext-diff", "--no-textconv", "--",
            if (exists) before.toString() else "/dev/null", after.toString()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        require(process.waitFor() in 0..1) { "Не удалось построить diff: $output" }
        val hunk = output.indexOf("@@")
        require(hunk >= 0) { "Изменение не удалось представить текстовым diff" }
        return "diff --git a/$path b/$path\n" + (if (!exists) "new file mode 100644\n" else "") +
            "--- ${if (exists) "a/$path" else "/dev/null"}\n+++ b/$path\n" + output.substring(hunk)
    }

    private fun entries(root: Path): List<FileEntry> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
            .map { root.relativize(it).toString().replace('\\', '/') }
            .filter { allowed(it) }.sorted().map { FileEntry(it, Files.size(checked(root, it))) }.toList()
    }

    private fun checked(root: Path, path: String): Path {
        require(allowed(path)) { "Недопустимый путь: $path" }
        val result = root.resolve(path).normalize()
        require(result.startsWith(root)) { "Выход за пределы проекта запрещён" }
        var part = root
        require(!Files.isSymbolicLink(part)) { "Символические ссылки запрещены" }
        root.relativize(result).forEach { segment ->
            part = part.resolve(segment)
            require(!Files.isSymbolicLink(part)) { "Символические ссылки запрещены: $path" }
        }
        return result
    }

    private fun allowed(path: String): Boolean = path.isNotBlank() && path.length < 300 &&
        path.split('/').all { it.matches(Regex("[a-zA-Z0-9_-][a-zA-Z0-9_.-]*")) && it != "." && it != ".." } &&
        path.substringAfterLast('.').lowercase() in setOf("kt", "kts", "md", "yml", "yaml", "json", "txt", "properties", "xml")

    private fun read(file: Path): String {
        require(Files.isRegularFile(file, NOFOLLOW_LINKS)) { "Файл не найден: ${file.fileName}" }
        require(Files.size(file) <= MAX_FILE_BYTES) { "Файл больше $MAX_FILE_BYTES байт" }
        return Files.readString(file).also { require('\u0000' !in it) { "Бинарные файлы не поддерживаются" } }
    }

    private fun <T> locked(id: String, action: (Path) -> T): T {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "Некорректный workspace_id" }
        val base = storage.resolve(id)
        require(Files.isDirectory(base, NOFOLLOW_LINKS) && !Files.isSymbolicLink(base)) { "Рабочая копия не найдена" }
        require(Files.isDirectory(base.resolve("project"), NOFOLLOW_LINKS) && Files.isDirectory(base.resolve("original"), NOFOLLOW_LINKS)) { "Неполная рабочая копия" }
        return synchronized(locks.getOrPut(id) { Any() }) { action(base) }
    }

    companion object {
        const val MAX_FILE_BYTES = 100_000
        fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

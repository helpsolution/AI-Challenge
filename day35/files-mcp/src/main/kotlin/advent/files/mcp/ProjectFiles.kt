package advent.files.mcp

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable data class FileEntry(val path: String, val bytes: Long)
@Serializable data class ProjectInfo(val directory: String, val name: String, val files: List<FileEntry>)
@Serializable data class WorkspaceInfo(val workspaceId: String, val directory: String, val sourceDirectory: String, val files: List<FileEntry>)
@Serializable data class FileText(val path: String, val sha256: String, val content: String, val numbered: String)
@Serializable data class Match(val path: String, val line: Int, val text: String)
@Serializable data class SearchResult(val matches: List<Match>, val truncated: Boolean)
@Serializable data class FileChange(val path: String, val kind: String, val content: String, val diff: String)
@Serializable data class ChangeSet(val workspaceId: String, val directory: String, val sourceDirectory: String,
    val sourceChangedPaths: List<String>, val patchApplicable: Boolean, val patchError: String?,
    val readFiles: List<String>, val changes: List<FileChange>)

/** Снимок локального проекта для анализа и отдельная копия для правок документации. */
class ProjectFiles(seed: Path, storage: Path) {
    private val seed = seed.toAbsolutePath().normalize()
    private val storage = storage.toAbsolutePath().normalize()
    private val reads = ConcurrentHashMap<String, MutableSet<String>>()
    private val locks = ConcurrentHashMap<String, Any>()

    init {
        require(Files.isDirectory(this.seed, NOFOLLOW_LINKS) && !Files.isSymbolicLink(this.seed)) { "Не найдена папка проекта: ${this.seed}" }
        require(!this.storage.startsWith(this.seed)) { "WORKSPACES_DIR должен находиться вне PROJECT_DIR" }
        Files.createDirectories(this.storage)
        require(!Files.isSymbolicLink(this.storage)) { "Папка рабочих копий не должна быть ссылкой" }
    }

    fun projectInfo() = ProjectInfo(seed.toString(), seed.fileName.toString(), entries(seed))

    fun createWorkspace(): WorkspaceInfo {
        val entries = entries(seed)
        require(entries.isNotEmpty()) { "В проекте нет поддерживаемых текстовых файлов" }
        require(entries.size <= MAX_FILES) { "В проекте больше $MAX_FILES файлов. Укажите отдельный модуль в PROJECT_DIR." }
        require(entries.sumOf { it.bytes } <= MAX_PROJECT_BYTES) { "Проект больше $MAX_PROJECT_BYTES байт. Укажите отдельный модуль." }
        require(entries.all { it.bytes <= MAX_FILE_BYTES }) { "Файл больше $MAX_FILE_BYTES байт: ${entries.first { it.bytes > MAX_FILE_BYTES }.path}" }
        val id = UUID.randomUUID().toString()
        val base = storage.resolve(id)
        Files.createDirectory(base)
        val original = Files.createDirectory(base.resolve("original"))
        val project = Files.createDirectory(base.resolve("project"))
        entries.forEach { entry ->
            val source = checked(seed, entry.path)
            val snapshot = original.resolve(entry.path)
            val target = project.resolve(entry.path)
            Files.createDirectories(snapshot.parent)
            Files.createDirectories(target.parent)
            Files.copy(source, snapshot)
            read(snapshot) // Проверить размер, UTF-8 и отсутствие NUL до передачи модели.
            Files.copy(snapshot, target) // Обе копии содержат одинаковый снимок, даже если источник меняется.
        }
        Files.writeString(base.resolve("source-path.txt"), seed.toString())
        reads[id] = ConcurrentHashMap.newKeySet()
        return WorkspaceInfo(id, project.toString(), seed.toString(), entries)
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
        val source = Path.of(Files.readString(base.resolve("source-path.txt")))
        val sourcePaths = if (Files.isDirectory(source, NOFOLLOW_LINKS)) entries(source).map { it.path }.toSet() else emptySet()
        val originalPaths = entries(original).map { it.path }.toSet()
        val changed = (sourcePaths + originalPaths).filter { path ->
            path !in sourcePaths || path !in originalPaths || runCatching {
                !Files.readAllBytes(checked(source, path)).contentEquals(Files.readAllBytes(checked(original, path)))
            }.getOrDefault(true)
        }.sorted()
        val patchError = checkPatch(original, changes.joinToString("") { it.diff })
        ChangeSet(id, project.toString(), source.toString(), changed, patchError == null, patchError, reads[id].orEmpty().sorted(), changes)
    }

    private fun checkPatch(original: Path, patch: String): String? {
        if (patch.isEmpty()) return null
        val process = ProcessBuilder("git", "apply", "--check", "--whitespace=nowarn", "-")
            .directory(original.toFile()).redirectErrorStream(true).apply {
                environment()["GIT_CEILING_DIRECTORIES"] = original.parent.toString()
            }.start()
        process.outputStream.bufferedWriter().use { it.write(patch) }
        val output = process.inputStream.bufferedReader().readText()
        return if (process.waitFor() == 0) null else output.ifBlank { "git apply --check завершился с ошибкой" }
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

    private fun entries(root: Path): List<FileEntry> {
        val result = mutableListOf<FileEntry>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != root && (dir.fileName.toString().startsWith('.') || dir.fileName.toString() in EXCLUDED_DIRS))
                    FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val path = root.relativize(file).toString().replace('\\', '/')
                if (attrs.isRegularFile && allowed(path)) result += FileEntry(path, Files.size(checked(root, path)))
                require(result.size <= MAX_FILES) { "В проекте больше $MAX_FILES файлов. Укажите отдельный модуль в PROJECT_DIR." }
                return FileVisitResult.CONTINUE
            }
        })
        return result.sortedBy { it.path }
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
        path.split('/').all { it.matches(Regex("[a-zA-Z0-9_-][a-zA-Z0-9_.-]*")) && it !in EXCLUDED_DIRS } &&
        path.substringAfterLast('/').lowercase().let { name ->
            !name.contains("secret") && !name.contains("credential") && !name.contains("token") &&
                name != "package-lock.json" && name != "composer.lock" && name != "yarn.lock"
        } && path.substringAfterLast('.').lowercase() in setOf("kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "vue", "html", "css", "sh", "go", "rs", "sql", "md", "yml", "yaml", "json", "txt", "properties", "xml")

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
        private val EXCLUDED_DIRS = setOf("build", "target", "node_modules", "dist", "vendor", "data", "coverage", "venv", "__pycache__")
        const val MAX_FILES = 1500
        const val MAX_PROJECT_BYTES = 15_000_000L
        const val MAX_FILE_BYTES = 100_000
        fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

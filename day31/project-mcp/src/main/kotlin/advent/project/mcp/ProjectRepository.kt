package advent.project.mcp

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Только фиксированные команды git; корень задаётся при запуске, а не аргументами LLM. */
class ProjectRepository(root: Path) {
    private val root = root.toRealPath()
    init { require(Files.isDirectory(this.root)) { "PROJECT_ROOT должен быть директорией" } }

    fun branch(): JsonObject {
        val branch = git("branch", "--show-current").trim().ifEmpty { null }
        val commit = git("rev-parse", "--short", "HEAD").trim()
        return buildJsonObject {
            put("branch", branch?.let(::JsonPrimitive) ?: JsonNull)
            put("detached", branch == null)
            put("commit", commit)
            put("capturedAt", Instant.now().toString())
        }
    }

    fun status(): JsonObject {
        // -z сохраняет имена с пробелами и кириллицей, без git quotePath.
        val tokens = git("status", "--porcelain=v1", "-z", "--untracked-files=normal").split('\u0000')
        val entries = mutableListOf<JsonObject>()
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i++]
            if (token.length < 4) continue
            val code = token.take(2)
            val path = token.drop(3)
            val original = if ('R' in code || 'C' in code) tokens.getOrNull(i++) else null
            if (!publicPath(path)) continue
            entries += buildJsonObject {
                put("status", code); put("path", path)
                original?.takeIf(::publicPath)?.let { put("originalPath", it) }
            }
        }
        return buildJsonObject {
            put("clean", entries.isEmpty())
            put("count", entries.size)
            put("changes", JsonArray(entries.take(100)))
            put("truncated", entries.size > 100)
            put("capturedAt", Instant.now().toString())
        }
    }

    fun files(scope: String, limit: Int): JsonObject {
        require(scope == "repo" || SCOPE.matches(scope)) { "scope: repo или dayN" }
        require(limit in 1..300) { "limit: от 1 до 300" }
        val dir = if (scope == "repo") root else root.resolve(scope)
        require(Files.isDirectory(dir) && dir.toRealPath().startsWith(root)) { "Нет проекта $scope" }
        val files = mutableListOf<String>()
        // Git учитывает .gitignore. Также добавляем файлы ещё не закоммиченного day31.
        git("ls-files", "--cached", "--others", "--exclude-standard", "-z").split('\u0000')
            .filter { it.isNotEmpty() && publicPath(it) && Files.exists(root.resolve(it)) &&
                (scope == "repo" || it.startsWith("$scope/")) }
            .distinct().sorted().forEach(files::add)
        return buildJsonObject {
            put("scope", scope); put("count", files.size)
            put("files", JsonArray(files.take(limit).map(::JsonPrimitive)))
            put("truncated", files.size > limit)
            put("capturedAt", Instant.now().toString())
        }
    }

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "--no-optional-locks", "-C", root.toString()) + args)
            .redirectErrorStream(true).start()
        val output = java.util.concurrent.CompletableFuture.supplyAsync {
            process.inputStream.use { it.readNBytes(2_000_001) }
        }
        if (!process.waitFor(8, TimeUnit.SECONDS)) {
            process.destroyForcibly(); output.cancel(true)
            error("git не ответил за 8 секунд")
        }
        val bytes = output.get(2, TimeUnit.SECONDS)
        check(bytes.size <= 2_000_000) { "Вывод git слишком большой" }
        check(process.exitValue() == 0) { "Ошибка git: ${bytes.toString(Charsets.UTF_8).take(300)}" }
        return bytes.toString(Charsets.UTF_8)
    }

    companion object {
        private val SCOPE = Regex("day[1-9]\\d*")
        fun publicPath(path: String): Boolean = path.split('/').none {
            it.startsWith('.') || it in setOf("data", "build", "node_modules", "материалы", "swarm-report") ||
                it.endsWith(".pem") || it.endsWith(".key") || it.endsWith(".db")
        } && !path.contains("..") && path !in setOf("CLAUDE.md", "Базовая информация.md", "Ссылки.md")
    }
}

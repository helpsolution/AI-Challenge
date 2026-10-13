package advent.review.diff

import java.io.ByteArrayOutputStream

enum class ChangeType(val label: String) {
    ADDED("новый"),
    MODIFIED("изменён"),
    DELETED("удалён"),
    RENAMED("переименован"),
}

data class DiffLine(val kind: Kind, val oldLine: Int?, val newLine: Int?, val text: String) {
    enum class Kind { ADDED, REMOVED, CONTEXT }
}

data class Hunk(val header: String, val newStart: Int, val newCount: Int, val lines: List<DiffLine>)

data class FileDiff(val path: String, val oldPath: String?, val type: ChangeType, val binary: Boolean, val hunks: List<Hunk>) {
    val added: Int = hunks.sumOf { hunk -> hunk.lines.count { it.kind == DiffLine.Kind.ADDED } }
    val removed: Int = hunks.sumOf { hunk -> hunk.lines.count { it.kind == DiffLine.Kind.REMOVED } }

    // Строки новой версии, которые видны в diff: только на них ревью может сослаться.
    val visibleLines: Map<Int, DiffLine> = hunks.flatMap { it.lines }.filter { it.newLine != null }.associateBy { it.newLine!! }
}

/** Разбор `git diff` в формате unified. Пути в кавычках git экранирует как строки C. */
object DiffParser {
    private val HUNK = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*""")

    private class Builder(var path: String) {
        var oldPath: String? = null
        var type = ChangeType.MODIFIED
        var binary = false
        val hunks = mutableListOf<Hunk>()

        fun build() = FileDiff(path, oldPath?.takeIf { type == ChangeType.RENAMED }, type, binary, hunks.toList())
    }

    fun parse(text: String): List<FileDiff> {
        val lines = text.split('\n').map { it.removeSuffix("\r") }
        val files = mutableListOf<FileDiff>()
        var current: Builder? = null
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("diff --git ")) {
                current?.let { files += it.build() }
                current = Builder(headerPath(line.removePrefix("diff --git ")))
                i++
                continue
            }
            val file = current
            val hunk = HUNK.matchEntire(line)
            when {
                file == null -> {}
                hunk != null -> { i = readHunk(lines, i, hunk, file); continue }
                line.startsWith("new file mode") -> file.type = ChangeType.ADDED
                line.startsWith("deleted file mode") -> file.type = ChangeType.DELETED
                line.startsWith("rename from ") -> { file.oldPath = unquote(line.removePrefix("rename from ")); file.type = ChangeType.RENAMED }
                line.startsWith("rename to ") -> file.path = unquote(line.removePrefix("rename to "))
                line.startsWith("Binary files ") || line == "GIT binary patch" -> file.binary = true
                line.startsWith("--- ") -> line.removePrefix("--- ").takeIf { it != "/dev/null" }?.let { file.oldPath = path(it, "a/") }
                line.startsWith("+++ ") -> line.removePrefix("+++ ").takeIf { it != "/dev/null" }?.let { file.path = path(it, "b/") }
            }
            i++
        }
        current?.let { files += it.build() }
        return files
    }

    // Счётчики из заголовка ханка решают, где он кончается: строка «--- x» внутри ханка — удалённое «-- x».
    private fun readHunk(lines: List<String>, start: Int, header: MatchResult, file: Builder): Int {
        var oldNo = header.groupValues[1].toInt()
        var oldLeft = header.groupValues[2].ifEmpty { "1" }.toInt()
        val newStart = header.groupValues[3].toInt()
        val newCount = header.groupValues[4].ifEmpty { "1" }.toInt()
        var newNo = newStart
        var newLeft = newCount
        val result = mutableListOf<DiffLine>()
        var i = start + 1
        while (i < lines.size && (oldLeft > 0 || newLeft > 0)) {
            val line = lines[i]
            when (line.firstOrNull()) {
                '+' -> { result += DiffLine(DiffLine.Kind.ADDED, null, newNo++, line.substring(1)); newLeft-- }
                '-' -> { result += DiffLine(DiffLine.Kind.REMOVED, oldNo++, null, line.substring(1)); oldLeft-- }
                ' ', null -> { result += DiffLine(DiffLine.Kind.CONTEXT, oldNo++, newNo++, line.drop(1)); oldLeft--; newLeft-- }
                '\\' -> {}
                else -> break
            }
            i++
        }
        while (i < lines.size && lines[i].startsWith("\\")) i++
        file.hunks += Hunk(lines[start], newStart, newCount, result)
        return i
    }

    // «a/P b/P» без кавычек: путь с пробелами однозначен, только если обе половины совпадают.
    private fun headerPath(header: String): String {
        if (header.endsWith('"')) {
            val at = header.lastIndexOf(" \"b/")
            if (at >= 0) return path(header.substring(at + 1), "b/")
        }
        val length = (header.length - 5) / 2
        if ((header.length - 5) % 2 == 0 && length > 0 && header.startsWith("a/") &&
            header.substring(2 + length, 5 + length) == " b/" && header.substring(2, 2 + length) == header.substring(5 + length)
        ) return header.substring(2, 2 + length)
        return header.substringAfterLast(" b/", header)
    }

    private fun path(raw: String, prefix: String) = unquote(raw.trimEnd('\t')).removePrefix(prefix)

    fun unquote(raw: String): String {
        if (raw.length < 2 || !raw.startsWith('"') || !raw.endsWith('"')) return raw
        val bytes = ByteArrayOutputStream()
        var i = 1
        while (i < raw.length - 1) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length - 1) {
                val next = raw[i + 1]
                if (next in '0'..'7') {
                    bytes.write(raw.substring(i + 1, i + 4).toInt(8))
                    i += 4
                } else {
                    bytes.write(ESCAPES[next]?.code ?: next.code)
                    i += 2
                }
                continue
            }
            val codePoint = raw.codePointAt(i)
            bytes.write(String(Character.toChars(codePoint)).toByteArray())
            i += Character.charCount(codePoint)
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private val ESCAPES = mapOf('n' to '\n', 't' to '\t', 'r' to '\r', 'a' to '\u0007', 'b' to '\b', 'f' to '\u000c', 'v' to '\u000b')
}

package advent.review.rag

data class CodeChunk(val path: String, val startLine: Int, val endLine: Int, val text: String) {
    val embeddingText: String get() = "$path\n$text"
}

/** Режет код по строкам: граница — пустая строка или объявление верхнего уровня, когда кусок набрал targetChars. */
class CodeChunker(private val targetChars: Int = 1400, private val maxChars: Int = 2200) {
    fun split(path: String, content: String): List<CodeChunk> {
        // Строка длиннее чанка бывает только в минифицированном коде; целиком её Ollama не примет.
        val lines = content.lines().map { it.take(maxChars) }
        val chunks = mutableListOf<CodeChunk>()
        var start = 0
        var size = 0
        fun flush(end: Int) {
            val first = (start until end).firstOrNull { lines[it].isNotBlank() }
            if (first != null) {
                // Хвостовые пустые строки не входят в диапазон, иначе ссылка «строки 10–25» укажет на пустоту.
                val text = lines.subList(first, end).joinToString("\n").trimEnd()
                chunks += CodeChunk(path, first + 1, first + text.lines().size, text)
            }
            start = end
            size = 0
        }
        lines.forEachIndexed { i, line ->
            val boundary = size >= targetChars && (line.isBlank() || DECLARATION.containsMatchIn(line))
            if (size > 0 && (boundary || size + line.length + 1 > maxChars)) flush(i)
            size += line.length + 1
        }
        flush(lines.size)
        return chunks
    }

    private companion object {
        val DECLARATION = Regex(
            """^\s{0,4}(?:@\w+|(?:(?:private|internal|public|protected|override|open|abstract|data|enum|sealed|inline|suspend|const|lateinit|companion|export|async|static)\s+)*(?:fun|class|object|interface|val|var|def|function)\b)""",
        )
    }
}

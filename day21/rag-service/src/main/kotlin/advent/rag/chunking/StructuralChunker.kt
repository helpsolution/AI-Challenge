package advent.rag.chunking

import advent.rag.document.Document

/**
 * Стратегия «по структуре». Режет Markdown по заголовкам: каждый раздел — отдельный чанк,
 * а цепочка заголовков над ним идёт в [StructuralChunk.section].
 *
 * Раздел длиннее [maxChunkSize] делится по всё более мелким границам: сначала по абзацам
 * (пустым строкам), потом по строкам — пунктам списка, строкам кода. Соседние куски собираются
 * в один чанк, пока влезают. Кусок, который не делится и так, режется по фиксированному размеру —
 * последний резерв, иначе чанк не влезет в контекст модели эмбеддингов. Текст без заголовков,
 * например код, — один раздел, и он делится так же.
 */
class StructuralChunker(private val maxChunkSize: Int = DEFAULT_MAX_CHUNK_SIZE) : Chunker<StructuralChunk> {
    init {
        require(maxChunkSize > 0) { "maxChunkSize должен быть больше 0, пришло $maxChunkSize" }
    }

    override fun split(document: Document): List<StructuralChunk> {
        val text = document.text
        return sections(text)
            .flatMap { fit(text, it) }
            .mapIndexed { i, span ->
                StructuralChunk(
                    chunkId = document.chunkId(i),
                    source = document.source,
                    title = document.title,
                    url = document.url,
                    section = span.section,
                    index = i,
                    start = span.start,
                    end = span.end,
                    text = text.substring(span.start, span.end),
                )
            }
    }

    /**
     * Разделы между заголовками. Раздел из одного заголовка пропускается: его имя и так есть
     * в пути вложенных разделов. `#` внутри блока кода — комментарий, а не заголовок.
     */
    private fun sections(text: String): List<Span> {
        val sections = mutableListOf<Span>()
        val path = ArrayDeque<Heading>()
        var start = 0
        var bodyStart = 0
        var inCode = false

        fun close(end: Int) {
            if (text.substring(bodyStart, end).isBlank()) return
            val section = path.joinToString(" › ") { it.title }.ifEmpty { null }
            sections += Span(start, end, section).trimmed(text)
        }

        var lineStart = 0
        while (lineStart < text.length) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it == -1) text.length else it + 1 }
            val line = text.substring(lineStart, lineEnd).trimEnd()
            if (line.trimStart().let { it.startsWith("```") || it.startsWith("~~~") }) {
                inCode = !inCode
            } else if (!inCode) {
                HEADING.matchEntire(line)?.let { match ->
                    close(lineStart)
                    val level = match.groupValues[1].length
                    while (path.isNotEmpty() && path.last().level >= level) path.removeLast()
                    path.addLast(Heading(level, match.groupValues[2]))
                    start = lineStart
                    bodyStart = lineEnd
                }
            }
            lineStart = lineEnd
        }
        close(text.length)
        return sections
    }

    /** Кусок, который влезает, — один чанк. Длинный делится по границам следующего уровня. */
    private fun fit(text: String, span: Span, level: Int = 0): List<Span> = when {
        span.length <= maxChunkSize -> listOf(span)
        level == SEPARATORS.size -> cut(text, span)
        else -> pack(text, pieces(text, span, SEPARATORS[level])).flatMap { fit(text, it, level + 1) }
    }

    /** Куски между разделителями, без пустых. */
    private fun pieces(text: String, span: Span, separator: Regex): List<Span> {
        val body = text.substring(span.start, span.end)
        val pieces = mutableListOf<Span>()
        var start = 0
        for (gap in separator.findAll(body)) {
            pieces += Span(span.start + start, span.start + gap.range.first, span.section)
            start = gap.range.last + 1
        }
        pieces += Span(span.start + start, span.end, span.section)
        return pieces.map { it.trimmed(text) }.filter { it.length > 0 }
    }

    /** Соседние куски склеиваются в один, пока результат влезает в предел. */
    private fun pack(text: String, pieces: List<Span>): List<Span> {
        val groups = mutableListOf<Span>()
        var group: Span? = null
        for (piece in pieces) {
            group = when {
                group == null -> piece
                // Заголовок не отрываем от текста под ним: если вместе не влезают, их поделит следующий уровень.
                piece.end - group.start <= maxChunkSize || group.isHeading(text) -> group.copy(end = piece.end)
                else -> {
                    groups += group
                    piece
                }
            }
        }
        group?.let { groups += it }
        return groups
    }

    /**
     * Последний резерв: кусок без абзацев и строк режется по фиксированному размеру без перекрытия.
     * Поровну, а не «полные куски и огрызок в конце»: в огрызке из пары слов искать нечего.
     */
    private fun cut(text: String, span: Span): List<Span> {
        val parts = Math.ceilDiv(span.length, maxChunkSize)
        return FixedSizeChunker(Math.ceilDiv(span.length, parts), overlap = 0)
            .windows(text.substring(span.start, span.end))
            .map { (start, end) -> Span(span.start + start, span.start + end, span.section) }
    }

    private fun Span.isHeading(text: String) = HEADING.matches(text.substring(start, end))

    /** Без пробелов и переводов строк по краям — границы остаются точными позициями в исходнике. */
    private fun Span.trimmed(text: String): Span {
        var s = start
        var e = end
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        return copy(start = s, end = e)
    }

    private data class Span(val start: Int, val end: Int, val section: String?) {
        val length get() = end - start
    }

    private data class Heading(val level: Int, val title: String)

    companion object {
        /** Около 800 токенов русского текста — внутри диапазона 500–1000 токенов из лекции. */
        const val DEFAULT_MAX_CHUNK_SIZE = 1000

        private val HEADING = Regex("""(#{1,6})\s+(.+)""")

        /** Границы по убыванию: абзацы, затем строки. */
        private val SEPARATORS = listOf(Regex("""\n\s*\n"""), Regex("""\n"""))
    }
}

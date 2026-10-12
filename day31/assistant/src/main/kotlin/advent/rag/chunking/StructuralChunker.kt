package advent.rag.chunking

class StructuralChunker(private val targetChars: Int = 1400, private val maxChars: Int = 2200) {
    init { require(targetChars > 0 && maxChars >= targetChars) }

    private data class Line(val start: Int, val end: Int, val value: String)
    private data class Heading(val level: Int, val title: String)
    private data class Part(val start: Int, val end: Int, val section: String?, val prefix: String = "", val suffix: String = "")

    fun split(document: Document): List<Chunk> {
        val text = document.text
        val lines = Regex("[^\n]+\n?|\n").findAll(text).map { Line(it.range.first, it.range.last + 1, it.value.trimEnd()) }.toList()
        val headings = mutableListOf<Heading>()
        val parts = mutableListOf<Part>()
        var section: String? = null
        var i = 0
        fun add(start: Int, end: Int, prefix: String = "", suffix: String = "") {
            var s = start; var e = end
            while (s < e && text[s].isWhitespace()) s++
            while (e > s && text[e - 1].isWhitespace()) e--
            if (s < e) parts += Part(s, e, section, prefix, suffix)
        }
        while (i < lines.size) {
            val line = lines[i]
            val heading = HEADING.matchEntire(line.value)
            when {
                line.value.isBlank() -> i++
                heading != null -> {
                    val level = heading.groupValues[1].length
                    while (headings.isNotEmpty() && headings.last().level >= level) headings.removeLast()
                    headings += Heading(level, heading.groupValues[2].trim().trimEnd('#').trim())
                    section = headings.joinToString(" › ") { it.title }
                    i++
                }
                FENCE.containsMatchIn(line.value) -> {
                    val fence = FENCE.find(line.value)!!.value.trim()
                    val begin = i++
                    while (i < lines.size && !lines[i].value.trim().matches(Regex("${Regex.escape(fence.first().toString())}{${fence.length},}\\s*"))) i++
                    val close = i.takeIf { it < lines.size }
                    val end = close?.let { lines[it].end } ?: text.length
                    if (end - line.start <= maxChars) add(line.start, end)
                    else {
                        // Fence повторяется в content; offsets по-прежнему указывают на исходный код.
                        val bodyEnd = close ?: lines.size
                        val prefix = lines[begin].value + "\n"
                        for (j in begin + 1 until bodyEnd) add(lines[j].start, lines[j].end, prefix, "\n$fence")
                    }
                    if (close != null) i++
                }
                i + 1 < lines.size && line.value.contains('|') && TABLE_SEPARATOR.matches(lines[i + 1].value.trim()) -> {
                    val start = i
                    i += 2
                    while (i < lines.size && lines[i].value.contains('|') && lines[i].value.isNotBlank()) i++
                    val header = text.substring(lines[start].start, lines[start + 1].end).trimEnd() + "\n"
                    if (lines[i - 1].end - line.start <= maxChars) add(line.start, lines[i - 1].end)
                    else {
                        for (j in start + 2 until i) add(lines[j].start, lines[j].end, header)
                    }
                }
                isGlossary(document, section) && DEFINITION.matches(line.value) -> {
                    val begin = i++
                    while (i < lines.size && lines[i].value.isNotBlank() && !LIST.containsMatchIn(lines[i].value) && !HEADING.matches(lines[i].value)) i++
                    add(lines[begin].start, lines[i - 1].end)
                }
                LIST.containsMatchIn(line.value) -> {
                    val begin = i++
                    while (i < lines.size && lines[i].value.isNotBlank() && !HEADING.matches(lines[i].value) && !FENCE.containsMatchIn(lines[i].value)) i++
                    val intro = parts.lastOrNull()?.takeIf { it.section == section && it.end - it.start < 450 && text.substring(it.start, it.end).trimEnd().endsWith(':') }
                    if (lines[i - 1].end - (intro?.start ?: line.start) <= maxChars) add(line.start, lines[i - 1].end)
                    else {
                        val prefix = intro?.let { text.substring(it.start, it.end) + "\n\n" }.orEmpty()
                        var itemStart = begin
                        for (j in begin + 1..i) {
                            if (j == i || LIST.containsMatchIn(lines[j].value)) {
                                add(lines[itemStart].start, lines[j - 1].end, prefix)
                                itemStart = j
                            }
                        }
                    }
                }
                else -> {
                    val begin = i++
                    while (i < lines.size && lines[i].value.isNotBlank() && !HEADING.matches(lines[i].value) && !LIST.containsMatchIn(lines[i].value) && !FENCE.containsMatchIn(lines[i].value) && !(i + 1 < lines.size && TABLE_SEPARATOR.matches(lines[i + 1].value.trim()))) i++
                    add(lines[begin].start, lines[i - 1].end)
                }
            }
        }
        val fitted = parts.flatMap { fit(text, it) }
        val packed = mutableListOf<Part>()
        for (part in fitted) {
            val previous = packed.lastOrNull()
            val definition = isGlossary(document, part.section) && DEFINITION.matches(text.substring(part.start, part.end).lineSequence().first())
            val previousDefinition = previous?.let { isGlossary(document, it.section) && DEFINITION.matches(text.substring(it.start, it.end).lineSequence().first()) } ?: false
            if (previous != null && previous.section == part.section && !definition && !previousDefinition &&
                previous.prefix == part.prefix && previous.suffix == part.suffix &&
                text.substring(previous.end, part.start).isBlank() &&
                part.end - previous.start + part.prefix.length + part.suffix.length <=
                    (if (text.substring(previous.start, previous.end).trimEnd().endsWith(':')) maxChars else targetChars)) {
                packed[packed.lastIndex] = previous.copy(end = part.end)
            } else packed += part
        }
        return packed.mapIndexed { index, p ->
            Chunk(document.chunkId(index), document.source, document.title, document.url, p.section,
                p.prefix, index, p.start, p.end, text.substring(p.start, p.end), p.suffix)
        }
    }

    private fun isGlossary(document: Document, section: String?) =
        GLOSSARY.containsMatchIn(document.title) || GLOSSARY.containsMatchIn(section.orEmpty())

    private fun fit(text: String, part: Part): List<Part> {
        val budget = maxChars - part.prefix.length - part.suffix.length
        require(budget > 0) { "Слишком длинная шапка блока в разделе ${part.section}" }
        if (part.end - part.start <= budget) return listOf(part)
        val result = mutableListOf<Part>()
        var start = part.start
        while (start < part.end) {
            val limit = minOf(start + budget, part.end)
            var end = limit
            if (limit < part.end) {
                val body = text.substring(start, limit)
                val sentence = SENTENCE.findAll(body).lastOrNull()?.range?.last?.plus(1)
                val space = body.indexOfLast { it.isWhitespace() }
                end = start + when {
                    sentence != null && sentence >= budget / 3 -> sentence
                    space >= budget / 3 -> space
                    else -> budget
                }
                if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            }
            require(end > start) { "Не удалось разделить блок" }
            result += part.copy(start = start, end = end)
            start = end
            while (start < part.end && text[start].isWhitespace()) start++
        }
        return result
    }

    companion object {
        const val VERSION = "markdown-blocks-v2"
        private val GLOSSARY = Regex("глоссар|glossary", RegexOption.IGNORE_CASE)
        private val HEADING = Regex("^ {0,3}(#{1,6})\\s+(.+)$")
        private val FENCE = Regex("^ {0,3}(`{3,}|~{3,})")
        private val LIST = Regex("^ {0,3}(?:[-+*]|\\d+[.)])\\s+")
        private val DEFINITION = Regex("^ {0,3}[-+*]\\s+\\*\\*.+?\\*\\*\\s*[-–—].*")
        private val TABLE_SEPARATOR = Regex("\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?")
        private val SENTENCE = Regex("[.!?](?:[»\"')]+)?(?=\\s)")
    }
}

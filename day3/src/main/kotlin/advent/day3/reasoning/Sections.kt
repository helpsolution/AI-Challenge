package advent.day3.reasoning

/**
 * Разбор ответа на markdown-разделы. Нужен группе экспертов: в задании требуется
 * увидеть решение каждого, а не только общий итог, — поэтому единый текст ответа
 * раскладывается по заголовкам «### Аналитик», «### Инженер» и так далее.
 */
object Sections {

    /** Пусто, если разделов меньше двух: разбивать монолитный ответ не на что. */
    fun split(text: String): List<Section> {
        val sections = mutableListOf<Section>()
        var title: String? = null
        val body = StringBuilder()

        fun flush() {
            val t = title ?: return
            sections += Section(t, body.toString().trim())
            body.setLength(0)
        }

        for (line in text.lineSequence()) {
            val heading = HEADING.matchEntire(line.trim())
            if (heading != null) {
                flush()
                title = heading.groupValues[1].trim().trim('*', '_', ':', ' ')
            } else if (title != null) {
                body.appendLine(line)
            }
        }
        flush()

        return if (sections.size >= 2) sections else emptyList()
    }

    private val HEADING = Regex("""#{2,4}\s+(.+)""")
}

data class Section(val title: String, val body: String)

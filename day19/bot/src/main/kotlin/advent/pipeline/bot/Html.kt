package advent.pipeline.bot

/**
 * Сообщения уходят в Telegram с parse_mode=HTML: только так ссылку можно спрятать за коротким [3].
 * Всё, что пришло извне — ответ модели, заголовки, ошибки, — сначала экранируется, иначе
 * символ «<» в заголовке статьи сломал бы разметку, и Telegram отверг бы сообщение целиком.
 */
object Html {
    fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    fun link(url: String, label: String): String =
        "<a href=\"${escape(url).replace("\"", "&quot;")}\">${escape(label)}</a>"

    /**
     * Сводка ссылается на статьи их номерами на Хабре: [1082364]. Номер из семи цифр читать неудобно,
     * поэтому здесь он превращается в короткую ссылку [n], где n — место статьи в источниках отчёта.
     * Номер, которого нет в источниках, остаётся текстом.
     */
    fun linkify(text: String, sources: List<Pair<String, String>>): String {
        val positions = sources.withIndex().associate { (index, source) -> source.first to (index + 1 to source.second) }
        return CITATION.replace(escape(text)) { match ->
            positions[match.groupValues[1]]?.let { (number, url) -> link(url, "[$number]") } ?: match.value
        }
    }

    /**
     * Делит сообщение по строкам, чтобы каждый кусок влез в лимит Telegram.
     * Теги в тексте бота не переходят через перевод строки, поэтому разметка в кусках остаётся целой.
     */
    fun split(text: String, limit: Int): List<String> {
        if (text.length <= limit) return listOf(text)
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (line in text.lines()) {
            if (current.isNotEmpty() && current.length + line.length + 1 > limit) {
                chunks += current.toString().trimEnd()
                current.clear()
            }
            current.append(line.take(limit)).append('\n')
        }
        if (current.isNotBlank()) chunks += current.toString().trimEnd()
        return chunks
    }

    private val CITATION = Regex("\\[(\\d{1,12})]")
}

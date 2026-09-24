package advent.habr.bot

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
     * Модель ссылается на статьи номерами [n], а не адресами: так она не может исказить или выдумать ссылку.
     * Здесь номер превращается в ссылку на статью из того же ответа MCP. Номер вне списка остаётся текстом.
     */
    fun linkify(text: String, links: List<String>): String =
        REFERENCE.replace(escape(text)) { match ->
            val index = match.groupValues[1].toInt() - 1
            links.getOrNull(index)?.let { link(it, match.value) } ?: match.value
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

    private val REFERENCE = Regex("\\[(\\d{1,3})]")
}

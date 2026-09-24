package advent.pipeline.service

/**
 * Проверки входа, общие для summarize и save. Данные к этим шагам приходят не от поиска напрямую,
 * а из аргументов, которые собрала модель-агент, поэтому сервис ничему в них не верит на слово.
 */
internal object Validation {
    /** Номер статьи на Хабре. Цифры и только они: на номера ссылается текст сводки. */
    private val ID = Regex("\\d{1,12}")

    /** Ссылка на статью в сводке: [1082364]. */
    val CITATION = Regex("\\[(\\d{1,12})]")

    const val MAX_ITEMS = 20
    const val MAX_TITLE_LENGTH = 500
    const val MAX_LINK_LENGTH = 2000

    fun query(value: String?): String? {
        val topic = value?.trim()?.takeIf { it.isNotEmpty() }
        require(topic == null || topic.length <= SearchService.MAX_QUERY_LENGTH) {
            "Поле query длиннее ${SearchService.MAX_QUERY_LENGTH} символов"
        }
        return topic
    }

    fun id(value: String?, where: String): String {
        val id = value?.trim()
        require(!id.isNullOrEmpty()) { "$where: нет поля id" }
        require(ID.matches(id)) { "$where: id должен быть номером статьи из цифр, а не «${id.take(40)}»" }
        return id
    }

    fun title(value: String?, where: String): String {
        val title = value?.trim()
        require(!title.isNullOrEmpty()) { "$where: нет поля title" }
        require(title.length <= MAX_TITLE_LENGTH) { "$where: title длиннее $MAX_TITLE_LENGTH символов" }
        return title
    }

    /** Ссылку потом показывают человеку: javascript: и прочие схемы не пускаем. */
    fun link(value: String?, where: String): String {
        val link = value?.trim()
        require(!link.isNullOrEmpty()) { "$where: нет поля link" }
        require(link.length <= MAX_LINK_LENGTH && (link.startsWith("https://") || link.startsWith("http://"))) {
            "$where: link должен быть адресом http(s)"
        }
        return link
    }

    fun <T> items(values: List<T>?, field: String): List<T> {
        require(!values.isNullOrEmpty()) { "Поле $field пустое или не передано" }
        require(values.size <= MAX_ITEMS) { "В $field больше $MAX_ITEMS элементов" }
        return values
    }

    fun uniqueIds(ids: List<String>, field: String) {
        val repeated = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(repeated.isEmpty()) { "В $field повторяются id: ${repeated.joinToString(", ")}" }
    }

    fun citations(text: String): Set<String> = CITATION.findAll(text).map { it.groupValues[1] }.toSet()
}

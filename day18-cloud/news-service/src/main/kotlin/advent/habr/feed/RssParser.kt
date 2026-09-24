package advent.habr.feed

import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException

/** Одна статья из ленты — ровно то, что нужно для сводки. */
data class FeedItem(
    val title: String,
    val link: String,
    val author: String?,
    /** Теги и хабы: у Хабра это элементы category. */
    val categories: List<String>,
    /** Начало статьи обычным текстом, без HTML. */
    val excerpt: String?,
    /** null, если лента не дала даты или дала нечитаемую. */
    val publishedAt: Instant?,
)

class FeedException(message: String) : RuntimeException(message)

/**
 * Разбор RSS 2.0 — формата, в котором Хабр отдаёт ленту.
 *
 * RSS — это XML с чужого сервера, поэтому DTD выключены: иначе лента с внешней сущностью
 * могла бы заставить сервер прочитать локальный файл или сходить по сети (XXE).
 * Разбор потоковый (StAX): лента не строится в памяти целиком деревом.
 */
object RssParser {
    fun parse(xml: ByteArray): List<FeedItem> {
        val factory = XMLInputFactory.newFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.IS_COALESCING, true)
        }
        // Кодировку читатель берёт из XML-декларации, поэтому на вход идут байты, а не строка.
        val reader = try {
            factory.createXMLStreamReader(ByteArrayInputStream(xml))
        } catch (e: XMLStreamException) {
            throw FeedException("лента не похожа на XML: ${e.message}")
        }

        val items = mutableListOf<FeedItem>()
        var depth = 0
        var itemDepth = -1
        var item: Draft? = null
        try {
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        depth++
                        val field = fieldName(reader.namespaceURI, reader.localName)
                        val draft = item
                        if (draft == null && field == "item") {
                            item = Draft()
                            itemDepth = depth
                        } else if (draft != null && depth == itemDepth + 1 && field in FIELDS) {
                            // elementText дочитывает элемент до закрывающего тега — он уже не придёт в цикл
                            draft.put(field!!, reader.elementText)
                            depth--
                        }
                    }

                    XMLStreamConstants.END_ELEMENT -> {
                        if (item != null && depth == itemDepth) {
                            item.build()?.let(items::add)
                            item = null
                        }
                        depth--
                    }
                }
                if (items.size >= MAX_ITEMS) break
            }
        } catch (e: XMLStreamException) {
            throw FeedException("лента не разобрана: ${e.message}")
        } finally {
            reader.close()
        }
        return items
    }

    /**
     * У RSS 2.0 свои элементы без пространства имён. Из чужих нужен только dc:creator — автор статьи;
     * atom:link и прочие пропускаем.
     */
    private fun fieldName(namespace: String?, name: String): String? = when {
        namespace.isNullOrEmpty() -> name
        namespace == DUBLIN_CORE && name == "creator" -> "creator"
        else -> null
    }

    private class Draft {
        private var title: String? = null
        private var link: String? = null
        private var guid: String? = null
        private var pubDate: String? = null
        private var creator: String? = null
        private var description: String? = null
        private val categories = mutableListOf<String>()

        fun put(name: String, text: String) {
            // description — это HTML, пробелы в нём схлопнутся при очистке от тегов
            if (name == "description") {
                description = text
                return
            }
            val value = text.normalizeSpace().takeIf { it.isNotEmpty() } ?: return
            when (name) {
                "title" -> title = value
                "link" -> link = value
                "guid" -> guid = value
                "pubDate" -> pubDate = value
                "creator" -> creator = value
                "category" -> if (categories.size < MAX_CATEGORIES) categories += value.take(MAX_CATEGORY_LENGTH)
            }
        }

        /** Статья без заголовка или без ссылки для сводки бесполезна — такую пропускаем. */
        fun build(): FeedItem? {
            // guid у Хабра — чистая ссылка, а в link дописаны utm-метки: сначала берём guid.
            val url = listOfNotNull(guid, link).firstOrNull(::isWebLink) ?: return null
            return FeedItem(
                title = title?.take(MAX_TITLE_LENGTH) ?: return null,
                link = url,
                author = creator?.take(MAX_AUTHOR_LENGTH),
                categories = categories.distinct(),
                excerpt = description?.let(::excerpt),
                publishedAt = pubDate?.let(::parseDate),
            )
        }
    }

    /**
     * Начало статьи обычным текстом. Хабр кладёт в description кусок HTML с картинками;
     * разметка модели не нужна и в сводку не попадёт, поэтому теги просто вырезаются.
     */
    private fun excerpt(html: String): String? {
        val text = decodeEntities(html.replace(TAG, " ")).normalizeSpace().removeSuffix("Читать далее").trim()
        if (text.isEmpty()) return null
        return if (text.length <= MAX_EXCERPT_LENGTH) text else text.take(MAX_EXCERPT_LENGTH).trimEnd() + "…"
    }

    private fun decodeEntities(text: String): String = ENTITY.replace(text) { match ->
        val name = match.groupValues[1]
        when {
            name.startsWith("#x", ignoreCase = true) -> name.drop(2).toIntOrNull(16)?.let(::codePoint)
            name.startsWith("#") -> name.drop(1).toIntOrNull()?.let(::codePoint)
            else -> NAMED_ENTITIES[name]
        } ?: match.value
    }

    private fun codePoint(code: Int): String? =
        if (Character.isValidCodePoint(code)) String(Character.toChars(code)) else null

    /** Ссылку потом показывают человеку: javascript: и прочие схемы в сводку не пускаем. */
    private fun isWebLink(value: String): Boolean =
        value.length <= MAX_LINK_LENGTH && (value.startsWith("https://") || value.startsWith("http://"))

    /** Дата в RSS 2.0 — RFC 822: «Thu, 24 Sep 2026 09:51:37 GMT». */
    private fun parseDate(value: String): Instant? = try {
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
    } catch (e: DateTimeParseException) {
        null
    }

    private fun String.normalizeSpace(): String = trim().replace(WHITESPACE, " ")

    private const val DUBLIN_CORE = "http://purl.org/dc/elements/1.1/"
    private val FIELDS = setOf("title", "link", "guid", "pubDate", "creator", "category", "description")
    private val WHITESPACE = Regex("\\s+")
    private val TAG = Regex("<[^>]*>")
    private val ENTITY = Regex("&(#?[A-Za-z0-9]{1,8});")
    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "laquo" to "«", "raquo" to "»", "mdash" to "—", "ndash" to "–", "hellip" to "…",
    )

    private const val MAX_ITEMS = 300
    private const val MAX_TITLE_LENGTH = 500
    private const val MAX_AUTHOR_LENGTH = 100
    private const val MAX_CATEGORIES = 20
    private const val MAX_CATEGORY_LENGTH = 100
    private const val MAX_EXCERPT_LENGTH = 300
    private const val MAX_LINK_LENGTH = 2000
}

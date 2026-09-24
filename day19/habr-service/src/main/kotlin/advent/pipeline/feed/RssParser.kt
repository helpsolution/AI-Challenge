package advent.pipeline.feed

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
    /** Номер статьи на Хабре из её адреса: по нему шаги конвейера сверяют, что статья дошла та же. */
    val id: String,
    val title: String,
    val link: String,
    val author: String?,
    /** Теги и хабы: у Хабра это элементы category. */
    val tags: List<String>,
    /** Начало статьи обычным текстом, без HTML. */
    val text: String?,
    /** null, если лента не дала даты или дала нечитаемую. */
    val publishedAt: Instant?,
)

class FeedException(message: String) : RuntimeException(message)

/**
 * Разбор RSS 2.0 — формата, в котором Хабр отдаёт и свежую ленту, и результаты поиска.
 *
 * RSS — это XML с чужого сервера, поэтому DTD выключены: иначе лента с внешней сущностью
 * могла бы заставить сервер прочитать локальный файл или сходить по сети (XXE).
 * Разбор потоковый (StAX): лента не строится в памяти целиком деревом.
 */
object RssParser {
    fun parse(xml: ByteArray, limit: Int): List<FeedItem> {
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
            while (reader.hasNext() && items.size < limit) {
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
            }
        } catch (e: XMLStreamException) {
            throw FeedException("лента не разобрана: ${e.message}")
        } finally {
            reader.close()
        }
        // Одна статья может попасть в ленту дважды (новость и её перепост в блоге) — в сводке она нужна один раз.
        return items.distinctBy { it.id }
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
        private val tags = mutableListOf<String>()

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
                "category" -> if (tags.size < MAX_TAGS) tags += value.take(MAX_TAG_LENGTH)
            }
        }

        /** Статья без заголовка, ссылки или номера для сводки бесполезна — такую пропускаем. */
        fun build(): FeedItem? {
            // guid у Хабра — чистая ссылка, а в link дописаны utm-метки: сначала берём guid.
            val url = listOfNotNull(guid, link).firstOrNull(::isWebLink) ?: return null
            return FeedItem(
                id = ARTICLE_ID.find(url.substringBefore('?'))?.groupValues?.get(1) ?: return null,
                title = title?.take(MAX_TITLE_LENGTH) ?: return null,
                link = url,
                author = creator?.take(MAX_AUTHOR_LENGTH),
                tags = tags.distinct(),
                text = description?.let(::excerpt),
                publishedAt = pubDate?.let(::parseDate),
            )
        }
    }

    /**
     * Начало статьи обычным текстом. Хабр кладёт в description кусок HTML с картинками и в конце —
     * ссылку «под кат» с произвольным текстом вроде «Читать далее» или «Узнать, как …».
     * Разметка модели не нужна, а текст ссылки — не часть статьи, поэтому ссылка вырезается целиком.
     */
    private fun excerpt(html: String): String? {
        val text = decodeEntities(html.replace(HABRACUT, " ").replace(TAG, " ")).normalizeSpace()
        if (text.isEmpty()) return null
        return if (text.length <= MAX_TEXT_LENGTH) text else text.take(MAX_TEXT_LENGTH).trimEnd() + "…"
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

    /**
     * Типографика Хабра — неразрывные пробелы и дефисы, мягкие переносы — в тексте не видна, но модель,
     * переписывая статью в аргументы следующего шага, заменяет её обычными символами. Тогда проверка
     * передачи видит «изменённый заголовок» там, где человек разницы не найдёт. Поэтому такие символы
     * приводятся к обычным здесь, у источника.
     */
    private fun String.normalizeSpace(): String =
        replace(INVISIBLE, "").replace(HYPHENS, "-").replace(WHITESPACE, " ").trim()

    private const val DUBLIN_CORE = "http://purl.org/dc/elements/1.1/"
    private val FIELDS = setOf("title", "link", "guid", "pubDate", "creator", "category", "description")
    /** \s в Java не видит неразрывных пробелов: U+00A0, U+2007, U+202F перечислены отдельно. */
    private val WHITESPACE = Regex("[\\s\u00A0\u2007\u202F]+")
    private val HYPHENS = Regex("[\u2010\u2011]")
    /** Мягкий перенос и символы нулевой ширины. */
    private val INVISIBLE = Regex("[\u00AD\u200B\u200C\u200D\u2060\uFEFF]")
    private val TAG = Regex("<[^>]*>")
    private val HABRACUT = Regex("<a\\b[^>]*#habracut[^>]*>.*?</a>", RegexOption.DOT_MATCHES_ALL)
    /** Адреса статей Хабра кончаются номером: …/articles/1082364/, …/companies/otus/news/1082364/. */
    private val ARTICLE_ID = Regex("/(\\d{1,12})/?$")
    private val ENTITY = Regex("&(#?[A-Za-z0-9]{1,8});")
    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "laquo" to "«", "raquo" to "»", "mdash" to "—", "ndash" to "–", "hellip" to "…",
    )

    private const val MAX_TITLE_LENGTH = 500
    private const val MAX_AUTHOR_LENGTH = 100
    private const val MAX_TAGS = 10
    private const val MAX_TAG_LENGTH = 100
    /** Начало статьи. Больше не нужно: весь текст модель-агент перепишет в аргументы следующего шага. */
    private const val MAX_TEXT_LENGTH = 400
    private const val MAX_LINK_LENGTH = 2000
}

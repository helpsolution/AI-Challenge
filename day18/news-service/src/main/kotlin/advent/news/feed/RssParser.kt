package advent.news.feed

import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException

/** Одна новость из ленты — ровно то, что нужно для сводки. */
data class FeedItem(
    val title: String,
    val link: String,
    /** null, если лента не дала даты или дала нечитаемую. */
    val publishedAt: Instant?,
    /** Издание. Есть только у Google News: там в одной ленте статьи разных СМИ. */
    val source: String?,
)

class FeedException(message: String) : RuntimeException(message)

/**
 * Разбор RSS 2.0 — формата, в котором отдают ленты все источники каталога и Google News.
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
                        // У RSS 2.0 свои элементы без пространства имён: atom:link и dc:date пропускаем.
                        val plain = reader.namespaceURI.isNullOrEmpty()
                        val name = reader.localName
                        val draft = item
                        if (draft == null && plain && name == "item") {
                            item = Draft()
                            itemDepth = depth
                        } else if (draft != null && plain && depth == itemDepth + 1 && name in FIELDS) {
                            // elementText дочитывает элемент до закрывающего тега — он уже не придёт в цикл
                            draft.put(name, reader.elementText)
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

    private class Draft {
        private var title: String? = null
        private var link: String? = null
        private var guid: String? = null
        private var pubDate: String? = null
        private var source: String? = null

        fun put(name: String, text: String) {
            val value = text.normalizeSpace().takeIf { it.isNotEmpty() } ?: return
            when (name) {
                "title" -> title = value
                "link" -> link = value
                "guid" -> guid = value
                "pubDate" -> pubDate = value
                "source" -> source = value
            }
        }

        /** Новость без заголовка или без ссылки для сводки бесполезна — такую пропускаем. */
        fun build(): FeedItem? {
            val url = listOfNotNull(link, guid).firstOrNull(::isWebLink) ?: return null
            // Google News приписывает издание к заголовку: «Заголовок - РБК». Издание у нас есть отдельно.
            val cleanTitle = title
                ?.let { t -> source?.let { t.removeSuffix(" - $it") } ?: t }
                ?.take(MAX_TITLE_LENGTH)
                ?: return null
            return FeedItem(
                title = cleanTitle,
                link = url,
                publishedAt = pubDate?.let(::parseDate),
                source = source?.take(MAX_SOURCE_LENGTH),
            )
        }
    }

    /** Ссылку потом показывают человеку: javascript: и прочие схемы в сводку не пускаем. */
    private fun isWebLink(value: String): Boolean =
        value.length <= MAX_LINK_LENGTH && (value.startsWith("https://") || value.startsWith("http://"))

    /** Дата в RSS 2.0 — RFC 822, так её отдают все ленты каталога: «Wed, 23 Sep 2026 15:48:05 +0300». */
    private fun parseDate(value: String): Instant? = try {
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
    } catch (e: DateTimeParseException) {
        null
    }

    private fun String.normalizeSpace(): String = trim().replace(WHITESPACE, " ")

    private val FIELDS = setOf("title", "link", "guid", "pubDate", "source")
    private val WHITESPACE = Regex("\\s+")

    private const val MAX_ITEMS = 300
    private const val MAX_TITLE_LENGTH = 500
    private const val MAX_SOURCE_LENGTH = 100
    private const val MAX_LINK_LENGTH = 2000
}

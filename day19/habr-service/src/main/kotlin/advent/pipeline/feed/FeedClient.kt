package advent.pipeline.feed

import advent.pipeline.config.HabrProperties
import org.springframework.stereotype.Component
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Ходит в RSS Хабра. Адреса берутся только из application.yml, снаружи приходят лишь
 * текст запроса и число статей — и то и другое кодируется в параметры, а не подставляется в адрес.
 */
@Component
class FeedClient(private val properties: HabrProperties) {
    private val http = HttpClient.newBuilder()
        .connectTimeout(properties.fetchTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** Свежая лента: последние статьи Хабра, новые сверху. */
    fun latest(limit: Int): List<FeedItem> = fetch("${properties.articlesUrl}&limit=$limit", limit)

    /** Поиск по Хабру: статьи по запросу, тоже новые сверху. */
    fun search(query: String, limit: Int): List<FeedItem> =
        fetch("${properties.searchUrl}&q=${URLEncoder.encode(query, Charsets.UTF_8)}&limit=$limit", limit)

    private fun fetch(url: String, limit: Int): List<FeedItem> {
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(properties.fetchTimeout)
            .header("User-Agent", properties.userAgent)
            .header("Accept", "application/rss+xml, application/xml;q=0.9, text/xml;q=0.8")
            .GET()
            .build()

        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: IOException) {
            throw FeedException("Хабр недоступен: ${e.message ?: e::class.simpleName}")
        }

        return response.body().use { body ->
            if (response.statusCode() !in 200..299) {
                throw FeedException("Хабр ответил HTTP ${response.statusCode()}")
            }
            RssParser.parse(body.readLimited(properties.maxFeedSize.toBytes()), limit)
        }
    }

    /** Лента на гигабайт не должна съесть память сервиса: читаем не больше лимита. */
    private fun InputStream.readLimited(limit: Long): ByteArray {
        val bytes = readNBytes(limit.toInt() + 1)
        if (bytes.size > limit) throw FeedException("лента больше ${limit / 1024 / 1024} МБ")
        return bytes
    }
}

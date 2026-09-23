package advent.news.feed

import advent.news.config.NewsProperties
import org.springframework.stereotype.Component
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Скачивает ленту и отдаёт разобранные новости.
 * Адреса сюда приходят только из каталога и из шаблона Google News — от модели URL не берётся.
 */
@Component
class FeedClient(private val properties: NewsProperties) {
    // NORMAL следует редиректам, кроме понижения https → http: так переехал на новый адрес Интерфакс.
    private val http = HttpClient.newBuilder()
        .connectTimeout(properties.fetchTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun fetch(url: URI): List<FeedItem> {
        val request = HttpRequest.newBuilder(url)
            .timeout(properties.fetchTimeout)
            .header("User-Agent", properties.userAgent)
            .header("Accept", "application/rss+xml, application/xml;q=0.9, text/xml;q=0.8")
            .GET()
            .build()

        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: IOException) {
            throw FeedException("лента недоступна: ${e.message ?: e::class.simpleName}")
        }

        return response.body().use { body ->
            if (response.statusCode() !in 200..299) {
                throw FeedException("лента ответила HTTP ${response.statusCode()}")
            }
            RssParser.parse(body.readLimited(properties.maxFeedSize.toBytes()))
        }
    }

    /** Лента на гигабайт не должна съесть память сервиса: читаем не больше лимита. */
    private fun InputStream.readLimited(limit: Long): ByteArray {
        val bytes = readNBytes(limit.toInt() + 1)
        if (bytes.size > limit) throw FeedException("лента больше ${limit / 1024 / 1024} МБ")
        return bytes
    }
}

package advent.day9.llm

import org.slf4j.Logger
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration

/**
 * Механика одного POST с JSON: отправить, при сетевом сбое повторить, вернуть код и тело.
 *
 * Вынесено из клиентов, потому что у двух провайдеров она совпадает дословно, а различия —
 * в заголовках, ценах и разборе ответа. Копия этого кода в каждом клиенте расходилась бы
 * при первой же правке таймаутов.
 *
 * Взят `java.net.http.HttpClient` напрямую, без RestClient: у `RestClient` с
 * `JdkClientHttpRequestFactory` ожидание ответа не ограничивалось, и повисший запрос
 * держал агента заблокированным навсегда. У `HttpRequest.timeout` эта граница срабатывает.
 */
class HttpJson(connectTimeout: Duration) {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** Код ответа и тело как есть — без попыток что-либо истолковать. */
    data class Result(val status: Int, val body: String)

    fun post(
        endpoint: URI,
        headers: Map<String, String>,
        body: String,
        readTimeout: Duration,
        log: Logger,
    ): Result = try {
        send(endpoint, headers, body, readTimeout, log)
    } catch (e: HttpConnectTimeoutException) {
        // Соединиться не удалось вовсе. Отдельно от таймаута ответа: причины разные —
        // здесь сеть или недоступный хост, там долгий ответ модели, — и путать их
        // в одном сообщении значит отправлять читателя лога искать не то.
        throw LlmException("Не удалось соединиться с провайдером: ${e.message}", cause = e)
    } catch (e: HttpTimeoutException) {
        // Повторять таймаут смысла нет: второе ожидание будет таким же долгим.
        throw LlmException("Провайдер не ответил за ${readTimeout.toSeconds()} с", cause = e)
    } catch (e: IOException) {
        // Обрыв соединения лечится повтором: ответа мы не получили, дублировать нечего.
        log.warn("Сетевой сбой при обращении к провайдеру, повторяю: {}", e.message)
        try {
            send(endpoint, headers, body, readTimeout, log)
        } catch (retry: IOException) {
            throw LlmException("Не удалось получить ответ от провайдера: ${retry.message}", cause = retry)
        }
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw LlmException("Обращение к провайдеру прервано", cause = e)
    }

    private fun send(
        endpoint: URI,
        headers: Map<String, String>,
        body: String,
        readTimeout: Duration,
        log: Logger,
    ): Result {
        val request = HttpRequest.newBuilder(endpoint)
            // Граница ожидания ответа. Без неё повисший запрос держит агента навсегда.
            .timeout(readTimeout)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .POST(HttpRequest.BodyPublishers.ofString(body, UTF_8))
            .build()

        // Сырые байты запроса и ответа — самый нижний уровень: что именно ушло в сеть.
        // Логируем только тело. Заголовки не логируем никогда: в них лежит API-ключ.
        log.trace("HTTP POST {} — тело запроса ({} байт):\n{}", endpoint, body.toByteArray(UTF_8).size, body)

        val response = client.send(request, HttpResponse.BodyHandlers.ofString(UTF_8))
        val result = Result(response.statusCode(), response.body().orEmpty())
        log.trace("HTTP {} — тело ответа:\n{}", result.status, result.body)
        return result
    }
}

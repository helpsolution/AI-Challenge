package advent.day11.llm

import org.slf4j.Logger
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class HttpJson(connectTimeout: Duration) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    data class Result(val status: Int, val body: String)

    fun post(
        endpoint: URI,
        headers: Map<String, String>,
        body: String,
        readTimeout: Duration,
        log: Logger,
    ): Result {
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(readTimeout)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .POST(HttpRequest.BodyPublishers.ofString(body, UTF_8))
            .build()

        val started = System.nanoTime()
        log.debug("HTTP POST {} - ожидание до {} с", endpoint, readTimeout.toSeconds())
        log.trace("HTTP POST {} - тело запроса ({} байт):\n{}", endpoint, body.toByteArray(UTF_8).size, body)
        val future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString(UTF_8))
        val response = try {
            future.get(readTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            log.warn("HTTP POST {} - нет ответа за {} с", endpoint, readTimeout.toSeconds())
            throw LlmException("Провайдер не ответил за ${readTimeout.toSeconds()} с", cause = e)
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw LlmException("Обращение к провайдеру прервано", cause = e)
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            log.warn("HTTP POST {} - сбой через {} мс: {}", endpoint, elapsedMillis(started), cause.toString())
            when (cause) {
                is HttpConnectTimeoutException -> throw LlmException("Не удалось соединиться с провайдером: ${cause.message}", cause = cause)
                is HttpTimeoutException -> throw LlmException("Провайдер не ответил за ${readTimeout.toSeconds()} с", cause = cause)
                is IOException -> throw LlmException("Не удалось получить ответ от провайдера: ${cause.message}", cause = cause)
                else -> throw LlmException("Ошибка обращения к провайдеру: ${cause.message}", cause = cause)
            }
        }

        val result = Result(response.statusCode(), response.body().orEmpty())
        log.debug("HTTP POST {} - статус {} за {} мс", endpoint, result.status, elapsedMillis(started))
        log.trace("HTTP {} - тело ответа:\n{}", result.status, result.body)
        return result
    }

    private fun elapsedMillis(started: Long): Long = (System.nanoTime() - started) / 1_000_000
}

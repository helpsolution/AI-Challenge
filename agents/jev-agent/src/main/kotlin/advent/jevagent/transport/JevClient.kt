package advent.jevagent.transport

import advent.jevagent.config.JevProperties
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets.UTF_8

data class TransportResult(
    val responseJson: String?,
    val status: Int?,
    val durationMs: Long,
    val error: String? = null,
)

/** HTTP transport only. It knows nothing about support questions or answer types. */
@Component
class JevClient(private val properties: JevProperties) {
    val endpoint: String = properties.baseUrl.trimEnd('/') + "/v1/systemone"
    val configured: Boolean get() = properties.apiKey.isNotBlank()
    private val http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build()

    fun send(requestJson: String): TransportResult {
        if (!configured) {
            return TransportResult(null, null, 0, "Не задан TYPESAFE_API_KEY. Добавьте его в .env и перезапустите приложение.")
        }

        val request = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(properties.requestTimeout)
            .header("Authorization", "Bearer ${properties.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestJson, UTF_8))
            .build()

        val started = System.nanoTime()
        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString(UTF_8))
            TransportResult(
                responseJson = response.body(),
                status = response.statusCode(),
                durationMs = (System.nanoTime() - started) / 1_000_000,
                error = if (response.statusCode() in 200..299) null else "TypeSafe вернул HTTP ${response.statusCode()}.",
            )
        } catch (e: HttpTimeoutException) {
            TransportResult(null, null, elapsedMs(started), "TypeSafe не ответил за ${properties.requestTimeout.seconds} секунд.")
        } catch (e: IOException) {
            TransportResult(null, null, elapsedMs(started), "Сетевая ошибка при обращении к TypeSafe: ${e.message ?: "нет деталей"}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            TransportResult(null, null, elapsedMs(started), "Запрос к TypeSafe прерван.")
        }
    }

    private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000
}

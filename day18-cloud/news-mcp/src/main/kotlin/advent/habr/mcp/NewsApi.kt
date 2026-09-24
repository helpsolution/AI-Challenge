package advent.habr.mcp

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.io.IOException
import kotlinx.serialization.json.Json

/**
 * Тонкий клиент к REST API сервиса статей. MCP-сервер сам ничего не хранит и не считает:
 * собирает и агрегирует сервис, а здесь вызовы инструментов переводятся в HTTP-запросы.
 */
class NewsApi(baseUrl: String) {
    private val base = baseUrl.trimEnd('/')

    private val client = HttpClient(CIO) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            // Включение сразу делает сбор, а лента может отвечать до 15 секунд.
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

    suspend fun status(): Status = send { client.get("$base/api/status") }.parse()

    suspend fun setEnabled(enabled: Boolean): Status = send {
        client.put("$base/api/enabled") {
            contentType(ContentType.Application.Json)
            setBody(EnabledRequest(enabled))
        }
    }.parse()

    suspend fun digest(minutes: Int?, afterId: Long?, limit: Int): Digest = send {
        client.get("$base/api/digest") {
            parameter("minutes", minutes)
            parameter("afterId", afterId)
            parameter("limit", limit)
        }
    }.parse()

    private suspend fun send(request: suspend () -> HttpResponse): HttpResponse = try {
        request()
    } catch (e: IOException) {
        val reason = e.message?.takeIf { it.isNotBlank() } ?: "соединение не установлено"
        throw NewsApiException("Сервис статей недоступен по адресу $base: $reason")
    }

    private suspend inline fun <reified T> HttpResponse.parse(): T {
        if (status.value >= 400) {
            // Сервис объясняет отказ по-человечески — этот текст и увидит модель.
            val explained = runCatching { body<ApiError>().message }.getOrNull()
            throw NewsApiException(explained ?: "Сервис статей ответил ошибкой ${status.value}")
        }
        return body()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000L
        const val REQUEST_TIMEOUT_MS = 40_000L
    }
}

class NewsApiException(message: String) : RuntimeException(message)

package advent.pipeline.mcp

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Тонкий клиент к REST API habr-service. MCP-сервер сам ничего не хранит и не считает:
 * здесь вызовы инструментов переводятся в HTTP-запросы.
 */
class PipelineApi(baseUrl: String) {
    private val base = baseUrl.trimEnd('/')

    private val client = HttpClient(CIO) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            // summarize ждёт ответа DeepSeek, а он на десяток статей пишет сводку до минуты.
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

    suspend fun search(query: String?, limit: Int): SearchResult = send {
        client.get("$base/api/search") {
            parameter("query", query)
            parameter("limit", limit)
        }
    }.parse()

    /**
     * Тело уходит в сервис ровно тем JSON, который прислала модель: MCP-сервер ничего в нём не
     * переставляет и не дополняет. Поэтому то, что проверяет сервис, — это то, что передала модель.
     */
    suspend fun summarize(body: JsonObject): Summary = post("/api/summarize", body)

    suspend fun save(body: JsonObject): Report = post("/api/reports", body)

    suspend fun latest(): Report = send { client.get("$base/api/reports/latest") }.parse()

    private suspend inline fun <reified T> post(path: String, body: JsonObject): T = send {
        client.post("$base$path") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }.parse()

    private suspend fun send(request: suspend () -> HttpResponse): HttpResponse = try {
        request()
    } catch (e: IOException) {
        val reason = e.message?.takeIf { it.isNotBlank() } ?: "соединение не установлено"
        throw PipelineApiException("habr-service недоступен по адресу $base: $reason")
    }

    private suspend inline fun <reified T> HttpResponse.parse(): T {
        if (status.value >= 400) {
            // Сервис объясняет отказ по-человечески — этот текст и увидит модель.
            val explained = runCatching { body<ApiError>().message }.getOrNull()
            throw PipelineApiException(explained ?: "habr-service ответил ошибкой ${status.value}")
        }
        return body()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000L
        const val REQUEST_TIMEOUT_MS = 150_000L
    }
}

class PipelineApiException(message: String) : RuntimeException(message)

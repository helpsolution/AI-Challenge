package advent.news.mcp

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
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

/**
 * Тонкий клиент к REST API сервиса новостей — тому же, что открыт в Swagger.
 * MCP-сервер сам ничего не хранит и не считает: собирает и агрегирует сервис новостей,
 * а здесь вызовы инструментов переводятся в HTTP-запросы.
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
            // Подписка сразу делает первый сбор, а лента может отвечать до 15 секунд.
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

    suspend fun sources(): List<Source> = send { client.get("$base/api/sources") }.parse()

    suspend fun subscriptions(): List<Subscription> = send { client.get("$base/api/subscriptions") }.parse()

    suspend fun subscribeFeed(source: String, everyMinutes: Int?): SubscribeResult = send {
        client.post("$base/api/subscriptions/feeds") {
            contentType(ContentType.Application.Json)
            setBody(FeedSubscriptionRequest(source, everyMinutes))
        }
    }.parse()

    suspend fun subscribeTopic(query: String, everyMinutes: Int?): SubscribeResult = send {
        client.post("$base/api/subscriptions/topics") {
            contentType(ContentType.Application.Json)
            setBody(TopicSubscriptionRequest(query, everyMinutes))
        }
    }.parse()

    suspend fun unsubscribe(id: Long) {
        send { client.delete("$base/api/subscriptions/$id") }.ensureSuccess()
    }

    suspend fun digest(minutes: Int?, afterId: Long?, query: String?, subscriptionId: Long?, limit: Int): Digest = send {
        client.get("$base/api/digest") {
            parameter("minutes", minutes)
            parameter("afterId", afterId)
            parameter("query", query)
            parameter("subscriptionId", subscriptionId)
            parameter("limit", limit)
        }
    }.parse()

    private suspend fun send(request: suspend () -> HttpResponse): HttpResponse = try {
        request()
    } catch (e: IOException) {
        val reason = e.message?.takeIf { it.isNotBlank() } ?: "соединение не установлено"
        throw NewsApiException("Сервис новостей недоступен по адресу $base: $reason")
    }

    private suspend inline fun <reified T> HttpResponse.parse(): T {
        ensureSuccess()
        return body()
    }

    private suspend fun HttpResponse.ensureSuccess() {
        if (status.value < 400) return
        // Сервис объясняет отказ по-человечески — этот текст и увидит модель.
        val explained = runCatching { body<ApiError>().message }.getOrNull()
        throw NewsApiException(explained ?: "Сервис новостей ответил ошибкой ${status.value}")
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000L
        const val REQUEST_TIMEOUT_MS = 40_000L
    }
}

class NewsApiException(message: String) : RuntimeException(message)

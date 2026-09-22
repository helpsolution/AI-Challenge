package advent.users.mcp

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

/**
 * Тонкий клиент к REST API сервиса пользователей — тому же, что открыт в Swagger.
 * MCP-сервер сам ничего не хранит и не считает: он только переводит вызовы инструментов в HTTP-запросы.
 */
class UsersApi(baseUrl: String) {
    private val base = baseUrl.trimEnd('/')

    private val client = HttpClient(CIO) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

    suspend fun create(name: String, email: String): User = send {
        client.post("$base/api/users") {
            contentType(ContentType.Application.Json)
            setBody(CreateUserRequest(name, email))
        }
    }.parse()

    suspend fun find(query: String, limit: Int): List<User> = send {
        client.get("$base/api/users") {
            parameter("query", query)
            parameter("limit", limit)
        }
    }.parse()

    private suspend fun send(request: suspend () -> HttpResponse): HttpResponse = try {
        request()
    } catch (e: IOException) {
        val reason = e.message?.takeIf { it.isNotBlank() } ?: "соединение не установлено"
        throw UsersApiException("Сервис пользователей недоступен по адресу $base: $reason")
    }

    private suspend inline fun <reified T> HttpResponse.parse(): T {
        if (status.value >= 400) {
            // Сервис объясняет отказ по-человечески — этот текст и увидит модель.
            val explained = runCatching { body<ApiError>().message }.getOrNull()
            throw UsersApiException(explained ?: "Сервис пользователей ответил ошибкой ${status.value}")
        }
        return body()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000L
        const val REQUEST_TIMEOUT_MS = 20_000L
    }
}

class UsersApiException(message: String) : RuntimeException(message)

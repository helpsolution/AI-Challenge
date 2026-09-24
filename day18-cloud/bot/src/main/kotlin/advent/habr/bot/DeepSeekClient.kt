package advent.habr.bot

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

@Serializable
data class Message(val role: String, val content: String? = null)

@Serializable
private data class ChatRequest(val model: String, val messages: List<Message>, val temperature: Double)

@Serializable
private data class ChatResponse(val choices: List<Choice>)

@Serializable
private data class Choice(val message: Message)

class LlmException(message: String) : RuntimeException(message)

/**
 * Клиент DeepSeek (OpenAI-совместимый chat completions). Инструменты модели не передаются:
 * бот сам знает, какой инструмент звать, а модели остаётся только написать текст сводки.
 */
class DeepSeekClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) : AutoCloseable {
    private val client = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

    suspend fun complete(messages: List<Message>): String {
        val response = try {
            client.post(baseUrl.trimEnd('/') + COMPLETIONS_PATH) {
                contentType(ContentType.Application.Json)
                headers { append(HttpHeaders.Authorization, "Bearer $apiKey") }
                setBody(JSON.encodeToString(ChatRequest(model, messages, TEMPERATURE)))
            }
        } catch (e: IOException) {
            throw LlmException("Не удалось связаться с LLM: ${e.message ?: "соединение не установлено"}")
        }

        val body = response.bodyAsText()
        // Ответ провайдера показываем как есть: по пересказу не понять, что именно пошло не так.
        if (response.status.value >= 400) throw LlmException("LLM вернул ошибку ${response.status.value}: ${body.take(300)}")

        return runCatching { JSON.decodeFromString<ChatResponse>(body) }
            .getOrElse { throw LlmException("Ответ LLM не разобран: ${body.take(300)}") }
            .choices.firstOrNull()?.message?.content?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw LlmException("LLM вернул пустой ответ")
    }

    override fun close() = client.close()

    private companion object {
        const val COMPLETIONS_PATH = "/chat/completions"
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val REQUEST_TIMEOUT_MS = 120_000L
        const val TEMPERATURE = 0.2

        @OptIn(ExperimentalSerializationApi::class)
        val JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}

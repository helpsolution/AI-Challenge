package advent.pipeline.bot

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
import kotlinx.io.IOException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * Клиент DeepSeek (OpenAI-совместимый chat completions) для агента: модель получает список
 * инструментов MCP и сама решает, какой звать. Единственное место бота, которое знает API-ключ.
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

    suspend fun complete(messages: List<Message>, tools: List<ToolDefinition>): Message {
        val requestBody = JSON.encodeToString(
            ChatRequest(
                model = model,
                messages = messages,
                tools = tools.takeIf { it.isNotEmpty() },
                temperature = TEMPERATURE,
                maxTokens = MAX_TOKENS,
            ),
        )
        val response = try {
            client.post(baseUrl.trimEnd('/') + COMPLETIONS_PATH) {
                contentType(ContentType.Application.Json)
                headers { append(HttpHeaders.Authorization, "Bearer $apiKey") }
                setBody(requestBody)
            }
        } catch (e: IOException) {
            throw LlmException("Не удалось связаться с DeepSeek: ${e.message ?: "соединение не установлено"}")
        }

        val body = response.bodyAsText()
        if (response.status.value >= 400) throw LlmException(explain(response.status.value, body))

        val choice = runCatching { JSON.decodeFromString<ChatResponse>(body) }
            .getOrElse { throw LlmException("Ответ DeepSeek не разобран: ${body.take(300)}") }
            .choices.firstOrNull()
            ?: throw LlmException("DeepSeek вернул ответ без единого варианта")
        // Обрезанный по потолку ответ с вызовом инструмента — это обрезанный JSON аргументов.
        // Передать его дальше значит отдать шагу половину статей, поэтому это ошибка, а не ответ.
        if (choice.finishReason == "length") {
            throw LlmException("Ответ DeepSeek упёрся в потолок $MAX_TOKENS токенов. Попросите меньше статей.")
        }
        return choice.message
    }

    override fun close() = client.close()

    private fun explain(status: Int, body: String): String = when (status) {
        401 -> "DeepSeek отклонил ключ (401). Проверьте DEEPSEEK_API_KEY."
        402 -> "Недостаточно средств на балансе DeepSeek (402)."
        429 -> "Превышен лимит запросов к DeepSeek (429). Попробуйте позже."
        in 500..599 -> "DeepSeek временно недоступен ($status). Попробуйте позже."
        else -> "DeepSeek вернул ошибку $status: ${body.take(300)}"
    }

    private companion object {
        const val COMPLETIONS_PATH = "/chat/completions"
        const val CONNECT_TIMEOUT_MS = 10_000L
        /** Переписать десяток статей в аргументы вызова — несколько тысяч токенов, это минута-две. */
        const val REQUEST_TIMEOUT_MS = 240_000L
        const val TEMPERATURE = 0.0
        const val MAX_TOKENS = 8192

        /**
         * encodeDefaults нужен ради поля type = "function": без него провайдер получит
         * описание инструмента без типа. explicitNulls выключен, чтобы незаполненные
         * content и tool_calls не уезжали на сервер как null.
         */
        @OptIn(ExperimentalSerializationApi::class)
        val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

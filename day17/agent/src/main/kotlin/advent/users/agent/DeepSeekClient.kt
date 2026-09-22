package advent.users.agent

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
 * Один обмен с моделью целиком: и то, что ушло, и то, что пришло.
 *
 * Тело запроса собирается строкой здесь, а ответ читается строкой и только потом разбирается.
 * Поэтому в [requestBody] и [responseBody] лежат ровно те байты, которыми обменялись
 * с провайдером, а не их реконструкция по разобранным объектам.
 */
data class LlmExchange(
    val url: String,
    val requestHeaders: Map<String, String>,
    val requestBody: String,
    val status: Int,
    val responseBody: String,
    val message: Message,
)

/**
 * Первый из двух клиентов агента — к языковой модели.
 * Единственное место, которое знает про API-ключ и сетевой адрес провайдера.
 */
class DeepSeekClient(private val config: AgentConfig) : AutoCloseable {
    private val client = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

    suspend fun complete(messages: List<Message>, tools: List<ToolDefinition>): LlmExchange {
        val url = config.deepSeekBaseUrl.trimEnd('/') + COMPLETIONS_PATH
        val requestBody = JSON.encodeToString(
            ChatRequest(
                model = config.model,
                messages = messages,
                tools = tools.takeIf { it.isNotEmpty() },
                temperature = TEMPERATURE,
            ),
        )

        val response = try {
            client.post(url) {
                contentType(ContentType.Application.Json)
                headers { append(HttpHeaders.Authorization, "Bearer ${config.deepSeekApiKey}") }
                setBody(requestBody)
            }
        } catch (e: IOException) {
            throw LlmException("Не удалось связаться с LLM: ${e.message ?: "соединение не установлено"}")
        }

        val responseBody = response.bodyAsText()
        if (response.status.value >= 400) {
            throw LlmException(explain(response.status.value, responseBody))
        }

        val message = runCatching { JSON.decodeFromString<ChatResponse>(responseBody) }
            .getOrElse { throw LlmException("Ответ LLM не разобран: ${responseBody.take(300)}") }
            .choices.firstOrNull()?.message
            ?: throw LlmException("LLM вернул ответ без единого варианта")

        return LlmExchange(
            url = url,
            // Ключ маскируется: по краям он узнаётся, но из лога не восстанавливается.
            requestHeaders = mapOf(
                "Content-Type" to ContentType.Application.Json.toString(),
                "Authorization" to "Bearer ${maskKey(config.deepSeekApiKey)}",
            ),
            requestBody = requestBody,
            status = response.status.value,
            responseBody = responseBody,
            message = message,
        )
    }

    override fun close() = client.close()

    private fun explain(status: Int, body: String): String = when (status) {
        401 -> "LLM отклонил ключ (401). Проверьте DEEPSEEK_API_KEY."
        402 -> "Недостаточно средств на балансе LLM-провайдера (402)."
        429 -> "Превышен лимит запросов к LLM (429). Попробуйте позже."
        in 500..599 -> "LLM временно недоступен ($status). Попробуйте позже."
        else -> "LLM вернул ошибку $status: ${body.take(300)}"
    }

    /** Вывод легко скопировать куда-то ещё, поэтому от ключа остаётся минимум: префикс и хвост. */
    private fun maskKey(key: String): String =
        if (key.length <= 12) "***" else "${key.take(3)}…${key.takeLast(4)}"

    private companion object {
        const val COMPLETIONS_PATH = "/chat/completions"
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val REQUEST_TIMEOUT_MS = 120_000L
        const val TEMPERATURE = 0.2

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

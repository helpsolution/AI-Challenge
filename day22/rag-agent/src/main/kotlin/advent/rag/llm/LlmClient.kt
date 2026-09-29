package advent.rag.llm

import advent.rag.http.HttpExchange
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.toEntity
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpClient
import java.time.Duration
import kotlin.time.TimeSource

/** Сообщение диалога: system — правила, user — вопрос. */
data class ChatMessage(val role: String, val content: String)

/** Ответ модели, цена запроса и сам обмен с DeepSeek. */
data class Completion(
    val text: String,
    val model: String,
    val promptTokens: Int?,
    val completionTokens: Int?,
    /** Почему модель остановилась: stop — закончила мысль, length — упёрлась в лимит токенов. */
    val finishReason: String?,
    val exchange: HttpExchange,
)

/** DeepSeek недоступен или ответил ошибкой. В сообщении — ответ провайдера как есть. */
class LlmException(message: String) : RuntimeException(message)

/**
 * Кубик «сообщения → ответ» из day19: генеративная модель DeepSeek, без инструментов и без истории.
 * JSON собирается и разбирается здесь же, чтобы в трассировке был ровно тот запрос, что ушёл по сети.
 */
@Component
class LlmClient(builder: RestClient.Builder, private val json: JsonMapper, private val properties: LlmProperties) {

    private val deepSeek: RestClient = builder
        .baseUrl(properties.baseUrl)
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${properties.apiKey}")
        .requestFactory(JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build())
            .apply { setReadTimeout(properties.timeout) })
        .build()

    fun complete(messages: List<ChatMessage>): Completion {
        if (properties.apiKey.isBlank()) throw LlmException("DEEPSEEK_API_KEY не задан: положите ключ в day22/.env")
        val body = json.writeValueAsString(ChatRequest(properties.model, messages, properties.temperature))
        val started = TimeSource.Monotonic.markNow()
        val raw = send(body)
        val exchange = HttpExchange(
            method = "POST",
            url = properties.baseUrl + COMPLETIONS_PATH,
            // Ключ не покидает сервер: в трассировку попадает только то, что заголовок был.
            requestHeaders = mapOf(
                HttpHeaders.AUTHORIZATION to "Bearer ***",
                HttpHeaders.CONTENT_TYPE to MediaType.APPLICATION_JSON_VALUE,
            ),
            requestBody = body,
            status = raw.statusCode.value(),
            responseBody = raw.body.orEmpty(),
            durationMs = started.elapsedNow().inWholeMilliseconds,
        )
        val response = try {
            json.readValue(exchange.responseBody, ChatResponse::class.java)
        } catch (e: JacksonException) {
            throw LlmException("Ответ DeepSeek не разобран: ${exchange.responseBody.take(300)}")
        }
        val choice = response.choices.firstOrNull()
        val text = choice?.message?.content?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw LlmException("DeepSeek вернул пустой ответ")
        return Completion(
            text = text,
            model = response.model,
            promptTokens = response.usage?.promptTokens,
            completionTokens = response.usage?.completionTokens,
            finishReason = choice.finishReason,
            exchange = exchange,
        )
    }

    private fun send(body: String) =
        try {
            deepSeek.post().uri(COMPLETIONS_PATH).contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toEntity<String>()
        } catch (e: RestClientResponseException) {
            // Ответ провайдера показываем как есть: по пересказу не понять, что именно пошло не так.
            throw LlmException("DeepSeek ответил ${e.statusCode.value()}: ${e.responseBodyAsString.take(500)}")
        } catch (e: ResourceAccessException) {
            throw LlmException("Нет ответа от DeepSeek по ${properties.baseUrl}: ${(e.cause ?: e).message}")
        } catch (e: RestClientException) {
            throw LlmException("DeepSeek не ответил: ${e.message}")
        }

    private companion object {
        const val COMPLETIONS_PATH = "/chat/completions"
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}

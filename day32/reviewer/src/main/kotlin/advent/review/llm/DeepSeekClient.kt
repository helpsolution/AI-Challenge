package advent.review.llm

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.boot.context.properties.ConfigurationProperties
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

@ConfigurationProperties("cloud-llm")
data class CloudLlmProperties(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: Double,
    val maxTokens: Int,
    val timeout: Duration,
    val inputPrice: Double,
    val outputPrice: Double,
)

data class ChatMessage(val role: String, val content: String)

data class Completion(
    val text: String,
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val finishReason: String?,
    val durationMs: Long,
)

// retryable — таймаут, 429 и 5xx; неверный ключ или запрос повтор не исправит.
class LlmException(message: String, val retryable: Boolean) : RuntimeException(message)

internal data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double,
    val thinking: Map<String, String> = mapOf("type" to "disabled"),
    @JsonProperty("max_tokens") val maxTokens: Int,
    @JsonProperty("response_format") val responseFormat: Map<String, String> = mapOf("type" to "json_object"),
)

internal data class ChatResponse(val model: String, val choices: List<Choice>, val usage: Usage?)

internal data class Choice(val message: ResponseMessage, @JsonProperty("finish_reason") val finishReason: String?)

internal data class ResponseMessage(val content: String?)

internal data class Usage(
    @JsonProperty("prompt_tokens") val promptTokens: Int,
    @JsonProperty("completion_tokens") val completionTokens: Int,
)

@Component
class DeepSeekClient(builder: RestClient.Builder, private val json: JsonMapper, private val properties: CloudLlmProperties) {
    val model: String get() = properties.model

    private val deepSeek: RestClient = builder
        .baseUrl(properties.baseUrl)
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${properties.apiKey}")
        .requestFactory(JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build())
            .apply { setReadTimeout(properties.timeout) })
        .build()

    fun cost(promptTokens: Int, completionTokens: Int): Double =
        (promptTokens * properties.inputPrice + completionTokens * properties.outputPrice) / 1_000_000

    // json_object гарантирует только синтаксис JSON; поля проверяет FindingValidator.
    fun complete(messages: List<ChatMessage>): Completion {
        if (properties.apiKey.isBlank()) throw LlmException("DEEPSEEK_API_KEY не задан", retryable = false)
        val body = json.writeValueAsString(ChatRequest(properties.model, messages, properties.temperature, maxTokens = properties.maxTokens))
        val started = TimeSource.Monotonic.markNow()
        val raw = try {
            deepSeek.post().uri(COMPLETIONS_PATH).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toEntity<String>().body.orEmpty()
        } catch (e: RestClientResponseException) {
            val status = e.statusCode.value()
            throw LlmException("DeepSeek ответил $status: ${e.responseBodyAsString.take(500)}", retryable = status == 429 || status >= 500)
        } catch (e: ResourceAccessException) {
            throw LlmException("Нет ответа от DeepSeek по ${properties.baseUrl}: ${(e.cause ?: e).message}", retryable = true)
        } catch (e: RestClientException) {
            throw LlmException("DeepSeek не ответил: ${e.message}", retryable = true)
        }
        val response = try {
            json.readValue(raw, ChatResponse::class.java)
        } catch (e: JacksonException) {
            throw LlmException("Ответ DeepSeek не разобран: ${raw.take(300)}", retryable = true)
        }
        val choice = response.choices.firstOrNull()
        val text = choice?.message?.content?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw LlmException("DeepSeek вернул пустой ответ", retryable = true)
        return Completion(
            text = text,
            model = response.model,
            promptTokens = response.usage?.promptTokens ?: 0,
            completionTokens = response.usage?.completionTokens ?: 0,
            finishReason = choice.finishReason,
            durationMs = started.elapsedNow().inWholeMilliseconds,
        )
    }

    private companion object {
        const val COMPLETIONS_PATH = "/chat/completions"
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}

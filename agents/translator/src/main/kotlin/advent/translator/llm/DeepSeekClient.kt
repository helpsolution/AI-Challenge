package advent.translator.llm

import advent.translator.config.DeepSeekProperties
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets.UTF_8

@Component
class DeepSeekClient(
    private val properties: DeepSeekProperties,
    private val objectMapper: ObjectMapper,
) : LlmClient {
    private val log = LoggerFactory.getLogger(javaClass)
    private val endpoint = URI.create(properties.baseUrl.trimEnd('/') + "/chat/completions")
    private val http = HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout)
        .build()

    override fun complete(messages: List<LlmMessage>): String {
        if (properties.apiKey.isBlank()) {
            throw LlmException("Не задан DEEPSEEK_API_KEY")
        }

        val body = objectMapper.writeValueAsString(
            CompletionRequest(
                model = properties.model,
                messages = messages,
                temperature = properties.temperature,
                maxTokens = properties.maxTokens,
                responseFormat = ResponseFormat("json_object"),
            ),
        )
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(properties.readTimeout)
            .header("Authorization", "Bearer ${properties.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, UTF_8))
            .build()

        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString(UTF_8))
        } catch (e: HttpTimeoutException) {
            throw LlmException("DeepSeek не ответил за ${properties.readTimeout.toSeconds()} секунд", e)
        } catch (e: IOException) {
            throw LlmException("Сетевая ошибка при обращении к DeepSeek: ${e.message}", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw LlmException("Обращение к DeepSeek прервано", e)
        }

        if (response.statusCode() !in 200..299) {
            log.warn("DeepSeek вернул HTTP {}", response.statusCode())
            val message = when (response.statusCode()) {
                401 -> "DeepSeek отклонил API-ключ (401)"
                402 -> "Недостаточно средств на балансе DeepSeek (402)"
                429 -> "Превышен лимит запросов DeepSeek (429)"
                else -> "DeepSeek вернул HTTP ${response.statusCode()}"
            }
            throw LlmException(message)
        }

        val completion = try {
            objectMapper.readValue(response.body(), CompletionResponse::class.java)
        } catch (e: Exception) {
            throw LlmException("DeepSeek вернул некорректный ответ API", e)
        }
        if (completion.choices.firstOrNull()?.finishReason == "length") {
            throw LlmException("Ответ DeepSeek обрезан: увеличьте DEEPSEEK_MAX_TOKENS")
        }
        return completion.choices.firstOrNull()?.message?.content?.takeIf { it.isNotBlank() }
            ?: throw LlmException("DeepSeek вернул пустой ответ")
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CompletionRequest(
    val model: String,
    val messages: List<LlmMessage>,
    val temperature: Double,
    @JsonProperty("max_tokens") val maxTokens: Int,
    @JsonProperty("response_format") val responseFormat: ResponseFormat,
)

data class ResponseFormat(val type: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CompletionResponse(val choices: List<CompletionChoice> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class CompletionChoice(
    val message: CompletionMessage? = null,
    @JsonProperty("finish_reason") val finishReason: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CompletionMessage(val content: String? = null)

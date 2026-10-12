package advent.rag.llm

import advent.rag.http.HttpExchange
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.toEntity
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpClient
import java.time.Duration
import kotlin.time.TimeSource

class DeepSeekClient(builder: RestClient.Builder, private val json: JsonMapper, private val properties: CloudLlmProperties) : ChatModel {

    override val provider = Provider.CLOUD
    override val model: String get() = properties.model

    private val deepSeek: RestClient = builder
        .baseUrl(properties.baseUrl)
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${properties.apiKey}")
        .requestFactory(JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build())
            .apply { setReadTimeout(properties.timeout) })
        .build()

    // Схему DeepSeek не принимает: json_object гарантирует только синтаксис JSON, поля проверяет GroundingChecker.
    override fun complete(messages: List<ChatMessage>, schema: JsonNode, maxTokens: Int, temperature: Double?): Completion {
        val body = json.writeValueAsString(ChatRequest(properties.model, messages, temperature ?: properties.temperature,
            maxTokens = maxTokens, responseFormat = mapOf("type" to "json_object")))
        val started = TimeSource.Monotonic.markNow()
        val raw = send(body)
        val exchange = HttpExchange(
            method = "POST",
            url = properties.baseUrl + COMPLETIONS_PATH,
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
            timings = null,
        )
    }

    private fun send(body: String) =
        try {
            deepSeek.post().uri(COMPLETIONS_PATH).contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toEntity<String>()
        } catch (e: RestClientResponseException) {
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

package advent.rag.llm

import advent.rag.http.HttpExchange
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.toEntity
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import kotlin.time.TimeSource

// Модель в Ollama на этом компьютере: нативный /api/chat, ответ одним JSON вместе с таймингами.
class OllamaChatClient(
    private val ollama: RestClient,
    private val json: JsonMapper,
    private val properties: LocalLlmProperties,
    override val model: String,
) : ChatModel {

    override val provider = Provider.LOCAL

    override fun complete(messages: List<ChatMessage>, schema: JsonNode, maxTokens: Int, temperature: Double?): Completion {
        val body = json.writeValueAsString(OllamaChatRequest(model, messages, schema,
            OllamaOptions(temperature ?: properties.temperature, maxTokens, properties.numCtx)))
        val started = TimeSource.Monotonic.markNow()
        val raw = try {
            ollama.post().uri(CHAT_PATH).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toEntity<String>()
        } catch (e: RestClientResponseException) {
            val hint = if (e.statusCode.value() == 404) ". Скачайте модель: ollama pull $model" else ""
            throw LlmException("Ollama ответила ${e.statusCode.value()} на $CHAT_PATH: ${e.responseBodyAsString.take(500)}$hint")
        } catch (e: ResourceAccessException) {
            throw LlmException("Нет ответа от Ollama по ${properties.baseUrl}: ${(e.cause ?: e).message}. Запущена ли Ollama?")
        } catch (e: RestClientException) {
            throw LlmException("Ollama не ответила: ${e.message}")
        }
        val exchange = HttpExchange(
            method = "POST",
            url = properties.baseUrl + CHAT_PATH,
            requestHeaders = mapOf(HttpHeaders.CONTENT_TYPE to MediaType.APPLICATION_JSON_VALUE),
            requestBody = body,
            status = raw.statusCode.value(),
            responseBody = raw.body.orEmpty(),
            durationMs = started.elapsedNow().inWholeMilliseconds,
        )
        val response = try {
            json.readValue(exchange.responseBody, OllamaChatResponse::class.java)
        } catch (e: JacksonException) {
            throw LlmException("Ответ Ollama не разобран: ${exchange.responseBody.take(300)}")
        }
        val text = response.message?.content?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw LlmException("Ollama вернула пустой ответ")
        return Completion(
            text = text,
            model = response.model,
            promptTokens = response.promptEvalCount,
            completionTokens = response.evalCount,
            // done_reason у Ollama совпадает по смыслу с finish_reason OpenAI: stop или length.
            finishReason = response.doneReason,
            exchange = exchange,
            timings = Timings(
                loadMs = (response.loadDuration ?: 0) / NANOS_IN_MS,
                promptMs = (response.promptEvalDuration ?: 0) / NANOS_IN_MS,
                generationMs = (response.evalDuration ?: 0) / NANOS_IN_MS,
                cachedPromptTokens = response.promptEvalCachedCount,
            ),
        )
    }

    private companion object {
        const val CHAT_PATH = "/api/chat"
        const val NANOS_IN_MS = 1_000_000
    }
}

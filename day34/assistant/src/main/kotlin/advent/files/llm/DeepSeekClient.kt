package advent.files.llm

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpClient
import java.time.Duration
import kotlin.time.TimeSource

@ConfigurationProperties("cloud-llm")
data class LlmProperties(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: Double,
    val maxTokens: Int,
    val timeout: Duration,
    val attempts: Int,
    val inputPrice: Double,
    val outputPrice: Double,
)

// retryable — таймаут, обрыв, 429 и 5xx; неверный ключ или запрос повтор не исправит.
class LlmException(message: String, val retryable: Boolean) : RuntimeException(message)

// Один ход модели: либо текст ответа, либо вызовы инструментов (текст при них — рассуждение вслух).
data class Turn(
    val content: String,
    val toolCalls: List<ToolCall>,
    val finishReason: String?,
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val durationMs: Long,
    val attempts: Int,
)

@Component
class DeepSeekClient(builder: RestClient.Builder, private val json: JsonMapper, private val properties: LlmProperties) {
    val model: String get() = properties.model
    val configured: Boolean get() = properties.apiKey.isNotBlank()

    private val deepSeek: RestClient = builder
        .baseUrl(properties.baseUrl)
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${properties.apiKey}")
        .requestFactory(JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build())
            .apply { setReadTimeout(properties.timeout) })
        .build()

    fun cost(promptTokens: Int, completionTokens: Int): Double =
        (promptTokens * properties.inputPrice + completionTokens * properties.outputPrice) / 1_000_000

    // onDelta получает текст по мере генерации; onRetry — причину повтора, после которого уже показанный текст недействителен.
    fun stream(messages: List<Message>, tools: List<ToolSpec>, toolChoice: String,
        onDelta: (String) -> Unit, onRetry: (String) -> Unit): Turn {
        if (!configured) throw LlmException("DEEPSEEK_API_KEY не задан", retryable = false)
        val body = json.writeValueAsString(ChatRequest(properties.model, messages, properties.temperature,
            properties.maxTokens, tools.ifEmpty { null }, toolChoice.takeIf { tools.isNotEmpty() }))
        var attempt = 1
        while (true) {
            try {
                return streamOnce(body, onDelta).copy(attempts = attempt)
            } catch (e: LlmException) {
                if (!e.retryable || attempt >= properties.attempts) throw e
                onRetry("Попытка $attempt из ${properties.attempts}: ${e.message}")
                Thread.sleep(RETRY_PAUSE_MS * attempt)
                attempt++
            }
        }
    }

    private fun streamOnce(body: String, onDelta: (String) -> Unit): Turn {
        val started = TimeSource.Monotonic.markNow()
        return try {
            deepSeek.post().uri(COMPLETIONS_PATH).contentType(MediaType.APPLICATION_JSON).body(body).exchange { _, response ->
                val status = response.statusCode.value()
                if (status >= 400) {
                    val error = response.body.readAllBytes().decodeToString().take(500)
                    throw LlmException("DeepSeek ответил $status: $error", retryable = status == 429 || status >= 500)
                }
                val content = StringBuilder()
                val calls = sortedMapOf<Int, PartialCall>()
                var finishReason: String? = null
                var model = properties.model
                var usage: Usage? = null
                response.body.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        val chunk = try {
                            json.readValue(data, StreamChunk::class.java)
                        } catch (e: JacksonException) {
                            throw LlmException("Кусок ответа DeepSeek не разобран: ${data.take(300)}", retryable = true)
                        }
                        chunk.model?.let { model = it }
                        chunk.usage?.let { usage = it }
                        val choice = chunk.choices.firstOrNull() ?: continue
                        choice.finishReason?.let { finishReason = it }
                        choice.delta?.content?.takeIf { it.isNotEmpty() }?.let { content.append(it); onDelta(it) }
                        choice.delta?.toolCalls?.forEach { delta ->
                            val call = calls.getOrPut(delta.index) { PartialCall() }
                            delta.id?.let { call.id = it }
                            delta.function?.name?.let { call.name.append(it) }
                            delta.function?.arguments?.let { call.arguments.append(it) }
                        }
                    }
                }
                if (finishReason == null) throw LlmException("Поток DeepSeek оборвался до конца ответа", retryable = true)
                val toolCalls = calls.values.map { ToolCall(it.id ?: "call_${it.name}", FunctionCall(it.name.toString(), it.arguments.toString().ifBlank { "{}" })) }
                if (content.isBlank() && toolCalls.isEmpty()) throw LlmException("DeepSeek вернул пустой ответ", retryable = true)
                Turn(content.toString(), toolCalls, finishReason, model, usage?.promptTokens ?: 0, usage?.completionTokens ?: 0,
                    started.elapsedNow().inWholeMilliseconds, 1)
            }
        } catch (e: ResourceAccessException) {
            throw LlmException("Нет ответа от DeepSeek по ${properties.baseUrl}: ${(e.cause ?: e).message}", retryable = true)
        } catch (e: RestClientException) {
            throw LlmException("DeepSeek не ответил: ${e.message}", retryable = true)
        }
    }

    private class PartialCall(var id: String? = null, val name: StringBuilder = StringBuilder(), val arguments: StringBuilder = StringBuilder())

    private companion object {
        const val COMPLETIONS_PATH = "/chat/completions"
        const val RETRY_PAUSE_MS = 2000L
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}

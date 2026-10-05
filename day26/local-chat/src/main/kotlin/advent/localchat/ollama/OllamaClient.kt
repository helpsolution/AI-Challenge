package advent.localchat.ollama

import advent.localchat.http.HttpExchange
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

data class ChatResult(
    val answer: String,
    val model: String,
    val metrics: Metrics,
    val exchange: HttpExchange,
)

/** Метрики генерации, которые Ollama возвращает в ответе, переведённые из наносекунд в миллисекунды. */
data class Metrics(
    val totalMs: Double?,
    val loadMs: Double?,
    val promptTokens: Int?,
    val promptMs: Double?,
    val promptTokensPerSecond: Double?,
    val answerTokens: Int?,
    val answerMs: Double?,
    val answerTokensPerSecond: Double?,
    val doneReason: String?,
) {
    internal companion object {
        fun of(r: ChatResponse) = Metrics(
            totalMs = r.totalDuration?.let(::millis),
            loadMs = r.loadDuration?.let(::millis),
            promptTokens = r.promptEvalCount,
            promptMs = r.promptEvalDuration?.let(::millis),
            promptTokensPerSecond = perSecond(r.promptEvalCount, r.promptEvalDuration),
            answerTokens = r.evalCount,
            answerMs = r.evalDuration?.let(::millis),
            answerTokensPerSecond = perSecond(r.evalCount, r.evalDuration),
            doneReason = r.doneReason,
        )

        private fun millis(nanos: Long) = nanos / 1_000_000.0

        private fun perSecond(tokens: Int?, nanos: Long?) =
            if (tokens != null && nanos != null && nanos > 0) tokens * 1_000_000_000.0 / nanos else null
    }
}

data class ModelStatus(
    val baseUrl: String,
    val version: String,
    val model: String,
    val installed: Boolean,
    val installedModels: List<String>,
    val family: String?,
    val parameterSize: String?,
    val quantization: String?,
    val maxContext: Int?,
    val diskBytes: Long?,
    /** null — модель не в памяти: первый запрос её загрузит, это видно по `loadMs`. */
    val loaded: LoadedModel?,
    val temperature: Double,
    val systemPrompt: String,
)

data class LoadedModel(val memoryBytes: Long, val vramBytes: Long, val context: Int?, val expiresAt: String?)

class OllamaException(message: String) : RuntimeException(message)

@Component
class OllamaClient(builder: RestClient.Builder, private val json: JsonMapper, private val properties: OllamaProperties) {

    private val ollama: RestClient = builder
        .baseUrl(properties.baseUrl)
        .requestFactory(JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build())
            .apply { setReadTimeout(properties.timeout) })
        .build()

    fun chat(messages: List<ChatMessage>): ChatResult {
        val body = json.writeValueAsString(
            ChatRequest(properties.model, messages, stream = false, options = Options(properties.temperature)))
        val started = TimeSource.Monotonic.markNow()
        val raw = call(CHAT_PATH) {
            ollama.post().uri(CHAT_PATH).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toEntity<String>()
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
        val response = parse<ChatResponse>(exchange.responseBody)
        val answer = response.message?.content?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw OllamaException("Ollama вернула пустой ответ: ${exchange.responseBody.take(300)}")
        return ChatResult(answer, response.model, Metrics.of(response), exchange)
    }

    fun status(): ModelStatus {
        val version = get<VersionResponse>("/api/version").version
        val installed = get<TagsResponse>("/api/tags").models
        val running = get<RunningResponse>("/api/ps").models
        val model = installed.firstOrNull { isConfigured(it.name) }
        val loaded = running.firstOrNull { isConfigured(it.name) }
        return ModelStatus(
            baseUrl = properties.baseUrl,
            version = version,
            model = properties.model,
            installed = model != null,
            installedModels = installed.map { it.name },
            family = model?.details?.family,
            parameterSize = model?.details?.parameterSize,
            quantization = model?.details?.quantizationLevel,
            maxContext = model?.details?.contextLength,
            diskBytes = model?.size,
            loaded = loaded?.let { LoadedModel(it.size, it.sizeVram, it.contextLength, it.expiresAt) },
            temperature = properties.temperature,
            systemPrompt = properties.systemPrompt,
        )
    }

    /** Ollama дописывает `:latest` к имени без тега: `llama3.2` в конфиге — это `llama3.2:latest` в списке. */
    private fun isConfigured(name: String) = name == properties.model || name == "${properties.model}:latest"

    private inline fun <reified T> get(path: String): T =
        parse(call(path) { ollama.get().uri(path).retrieve().toEntity<String>() }.body.orEmpty())

    private inline fun <reified T> parse(raw: String): T =
        try {
            json.readValue(raw, T::class.java)
        } catch (e: JacksonException) {
            throw OllamaException("Ответ Ollama не разобран: ${raw.take(300)}")
        }

    private fun <T> call(path: String, request: () -> T): T =
        try {
            request()
        } catch (e: RestClientResponseException) {
            throw OllamaException("Ollama ответила ${e.statusCode.value()} на $path: ${e.responseBodyAsString.take(500)}")
        } catch (e: ResourceAccessException) {
            val cause = e.cause ?: e
            throw OllamaException("Нет ответа от Ollama по ${properties.baseUrl}: ${cause.message ?: cause.javaClass.simpleName}." +
                " Запущена ли Ollama?")
        } catch (e: RestClientException) {
            throw OllamaException("Ollama не ответила на $path: ${e.message}")
        }

    private companion object {
        const val CHAT_PATH = "/api/chat"
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}

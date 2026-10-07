package advent.localllm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Serializable
data class Message(val role: String, val content: String)

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    val stream: Boolean,
    /** JSON-схема ответа: Ollama ограничивает генерацию так, что модель не может выйти за схему. */
    val format: JsonElement? = null,
    val options: Options,
)

@Serializable
private data class Options(val temperature: Double)

/** Ответ /api/chat целиком или одна строка стрима: в стриме метрики приходят только в последней, с `done: true`. */
@Serializable
private data class ChatResponse(
    val model: String = "",
    val message: Message? = null,
    val done: Boolean = false,
    val error: String? = null,
    @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,
    @SerialName("eval_count") val evalCount: Int? = null,
    @SerialName("eval_duration") val evalDuration: Long? = null,
    @SerialName("load_duration") val loadDuration: Long? = null,
    @SerialName("total_duration") val totalDuration: Long? = null,
)

class ChatResult(
    val content: String,
    val model: String,
    val promptTokens: Int?,
    val answerTokens: Int?,
    val tokensPerSecond: Double?,
    val loadSeconds: Double?,
    val totalSeconds: Double?,
    /** Тела запроса и ответа как есть — для `--verbose`. В стриме ответ — последняя строка с метриками. */
    val rawRequest: String,
    val rawResponse: String,
)

class ModelStatus(val baseUrl: String, val model: String, val version: String, val installed: Boolean, val loaded: Boolean)

class OllamaException(message: String) : RuntimeException(message)

/** Нативный API Ollama: `POST /api/chat` целиком или стримом и три GET для статуса модели. */
class OllamaClient(private val baseUrl: String, val model: String) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** Ответ одним JSON. С [schema] модель отвечает строго по JSON-схеме. */
    fun chat(messages: List<Message>, temperature: Double, schema: JsonElement? = null): ChatResult {
        val body = json.encodeToString(ChatRequest(model, messages, stream = false, schema, Options(temperature)))
        val response = send(post(body), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw OllamaException(rejected(response.statusCode(), response.body()))
        val r = json.decodeFromString<ChatResponse>(response.body())
        return result(r, r.message?.content.orEmpty(), body, response.body())
    }

    /** Стрим: Ollama шлёт NDJSON по токену в строке, каждый кусок текста сразу уходит в [onDelta]. */
    fun chatStream(messages: List<Message>, temperature: Double, onDelta: (String) -> Unit): ChatResult {
        val body = json.encodeToString(ChatRequest(model, messages, stream = true, options = Options(temperature)))
        val response = send(post(body), HttpResponse.BodyHandlers.ofLines())
        // Закрытие стрима строк обрывает HTTP-запрос: если браузер ушёл, Ollama перестаёт генерировать.
        response.body().use { lines ->
            if (response.statusCode() != 200) {
                throw OllamaException(rejected(response.statusCode(), lines.toList().joinToString("\n")))
            }
            val content = StringBuilder()
            for (line in lines.iterator()) {
                if (line.isBlank()) continue
                val chunk = json.decodeFromString<ChatResponse>(line)
                chunk.error?.let { throw OllamaException("Ollama прервала генерацию: $it") }
                val delta = chunk.message?.content.orEmpty()
                if (delta.isNotEmpty()) {
                    content.append(delta)
                    onDelta(delta)
                }
                if (chunk.done) return result(chunk, content.toString(), body, line)
            }
            throw OllamaException("Ollama оборвала стрим без строки done")
        }
    }

    /** Версия Ollama, скачана ли модель и загружена ли она сейчас в память. */
    fun status(): ModelStatus {
        val version = get("/api/version").jsonObject["version"]?.jsonPrimitive?.content.orEmpty()
        fun names(path: String) = get(path).jsonObject["models"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
        return ModelStatus(baseUrl, model, version, installed = model in names("/api/tags"), loaded = model in names("/api/ps"))
    }

    private fun get(path: String): JsonElement {
        val response = send(HttpRequest.newBuilder(URI.create("$baseUrl$path")).GET().build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw OllamaException(rejected(response.statusCode(), response.body(), path))
        return json.parseToJsonElement(response.body())
    }

    private fun post(body: String): HttpRequest = HttpRequest.newBuilder(URI.create("$baseUrl/api/chat"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()

    private fun <T> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> = try {
        http.send(request, handler)
    } catch (e: IOException) {
        throw OllamaException(
            "Ollama не отвечает на $baseUrl: ${listOfNotNull(e.javaClass.simpleName, e.message).joinToString(": ")}\n" +
                "Запустите приложение Ollama или `ollama serve`."
        )
    }

    private fun rejected(status: Int, body: String, path: String = "/api/chat") = "Ollama ответила $status на $path: $body"

    private fun result(r: ChatResponse, content: String, rawRequest: String, rawResponse: String) = ChatResult(
        content = content,
        model = r.model,
        promptTokens = r.promptEvalCount,
        answerTokens = r.evalCount,
        tokensPerSecond = r.evalCount?.let { n -> r.evalDuration?.takeIf { it > 0 }?.let { n * 1e9 / it } },
        loadSeconds = r.loadDuration?.let { it / 1e9 },
        totalSeconds = r.totalDuration?.let { it / 1e9 },
        rawRequest = rawRequest,
        rawResponse = rawResponse,
    )
}

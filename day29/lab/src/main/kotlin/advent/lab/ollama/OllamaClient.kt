package advent.lab.ollama

import org.springframework.http.MediaType
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.toEntity
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import kotlin.time.TimeSource

class OllamaException(message: String) : RuntimeException(message)

class ChatReply(
    val text: String,
    val response: ChatResponse,
    /** Тело запроса как есть — показывается в интерфейсе в блоке «Что ушло в Ollama». */
    val requestBody: String,
    /** Полное время HTTP-запроса: генерация, обработка промпта и сеть до localhost. */
    val wallMs: Long,
)

// Нативный API Ollama: генерация, загрузка и выгрузка модели, список моделей и сборка своей модели.
class OllamaClient(private val http: RestClient, private val json: JsonMapper, val baseUrl: String) {

    fun chat(request: ChatRequest): ChatReply {
        val body = json.writeValueAsString(request)
        val started = TimeSource.Monotonic.markNow()
        val raw = post(CHAT, body, request.model)
        val response = parse(raw, ChatResponse::class.java)
        return ChatReply(response.message?.content.orEmpty(), response, body, started.elapsedNow().inWholeMilliseconds)
    }

    /**
     * Загружает модель с заданными options и возвращает время загрузки в мс. В ответе на пустой промпт Ollama
     * не пишет load_duration (только done_reason: load), поэтому время меряется по часам вокруг запроса.
     */
    fun load(model: String, options: Map<String, Any>?): Long {
        val started = TimeSource.Monotonic.markNow()
        post(GENERATE, json.writeValueAsString(LoadRequest(model, options, KEEP_ALIVE)), model)
        return started.elapsedNow().inWholeMilliseconds
    }

    /** keep_alive: 0 выгружает модель сразу — следующий прогон начнётся с честной загрузки. */
    fun unload(model: String) {
        post(GENERATE, json.writeValueAsString(LoadRequest(model, null, "0")), model)
    }

    fun installed(): List<ModelEntry> = get("/api/tags", ModelList::class.java).models

    fun running(): List<ModelEntry> = get("/api/ps", ModelList::class.java).models

    fun show(model: String): ShowResponse = parse(post("/api/show", json.writeValueAsString(mapOf("model" to model)), model),
        ShowResponse::class.java)

    fun version(): String = get("/api/version", VersionResponse::class.java).version

    /** Собирает модель из базовой: системный промпт, параметры и примеры записываются в её Modelfile. */
    fun create(request: CreateRequest): String = post("/api/create", json.writeValueAsString(request), request.from)

    private fun post(path: String, body: String, model: String): String = call(path) {
        try {
            http.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toEntity<String>().body.orEmpty()
        } catch (e: RestClientResponseException) {
            val hint = if (e.statusCode.value() == 404) ". Скачайте модель: ollama pull $model" else ""
            throw OllamaException("Ollama ответила ${e.statusCode.value()} на $path: ${e.responseBodyAsString.take(500)}$hint")
        }
    }

    private fun <T : Any> get(path: String, type: Class<T>): T = parse(call(path) {
        http.get().uri(path).retrieve().body(String::class.java).orEmpty()
    }, type)

    private fun call(path: String, block: () -> String): String = try {
        block()
    } catch (e: ResourceAccessException) {
        throw OllamaException("Ollama не отвечает на $baseUrl$path: ${(e.cause ?: e).message}. Запустите приложение Ollama или `ollama serve`.")
    } catch (e: RestClientException) {
        throw OllamaException("Ollama не ответила на $path: ${e.message}")
    }

    private fun <T : Any> parse(raw: String, type: Class<T>): T = try {
        json.readValue(raw, type)
    } catch (e: JacksonException) {
        throw OllamaException("Ответ Ollama не разобран: ${raw.take(300)}")
    }

    private companion object {
        const val CHAT = "/api/chat"
        const val GENERATE = "/api/generate"
    }
}

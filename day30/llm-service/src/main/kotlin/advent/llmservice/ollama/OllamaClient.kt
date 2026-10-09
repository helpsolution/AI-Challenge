package advent.llmservice.ollama

import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.io.UncheckedIOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.stream.Stream

class OllamaException(message: String) : RuntimeException(message)

/** Ollama отказала до генерации: промпт не помещается в окно. Числа — токенизатором самой модели. */
class ContextOverflowException(val promptTokens: Int, val contextTokens: Int) :
    RuntimeException("request ($promptTokens tokens) exceeds the available context size ($contextTokens tokens)")

/** Нативный API Ollama: генерация стримом, что загружено в память и версия. */
class OllamaClient(val baseUrl: String, private val json: JsonMapper) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    /** Открывает стрим генерации. Отказ до первого токена — окно контекста, нет модели — бросается здесь. */
    fun chat(request: OllamaChatRequest): Generation {
        val post = HttpRequest.newBuilder(URI.create("$baseUrl/api/chat"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request)))
            .build()
        val response = send(post, HttpResponse.BodyHandlers.ofLines())
        if (response.statusCode() != 200) {
            val body = response.body().use { it.toList().joinToString("\n") }
            throw rejected(response.statusCode(), body)
        }
        return Generation(response.body())
    }

    fun running(): List<RunningModel> = get("/api/ps", RunningModels::class.java).models

    fun version(): String = get("/api/version", OllamaVersion::class.java).version

    /**
     * Стрим ответа. Закрытие обрывает HTTP-запрос, и Ollama перестаёт генерировать: если клиент ушёл,
     * модель не досчитывает ответ впустую.
     */
    inner class Generation(private val lines: Stream<String>) : AutoCloseable {

        /** Передаёт куски текста в [onDelta] по мере генерации и возвращает последнюю строку с метриками. */
        fun collect(onDelta: (String) -> Unit): OllamaChunk = try {
            var last: OllamaChunk? = null
            for (line in lines.iterator()) {
                if (line.isBlank()) continue
                val chunk = parse(line, OllamaChunk::class.java)
                chunk.error?.let { throw OllamaException("Ollama прервала генерацию: $it") }
                chunk.message?.content?.takeIf { it.isNotEmpty() }?.let(onDelta)
                if (chunk.done) {
                    last = chunk
                    break
                }
            }
            last ?: throw OllamaException("Ollama оборвала стрим без строки done")
        } catch (e: UncheckedIOException) {
            throw OllamaException("Ollama оборвала стрим: ${e.cause?.message}")
        }

        override fun close() = lines.close()
    }

    /**
     * Ollama 0.40 кладёт ошибку llama-server строкой внутрь своей:
     * `{"error":"{\"error\":{\"type\":\"exceed_context_size_error\",\"n_prompt_tokens\":4517,\"n_ctx\":2048,…}}"}`.
     */
    private fun rejected(status: Int, body: String): RuntimeException {
        val outer = runCatching { json.readTree(body).path("error").asString() }.getOrNull().orEmpty()
        val inner = runCatching { json.readTree(outer).path("error") }.getOrNull()
        if (inner != null && inner.path("type").asString() == "exceed_context_size_error") {
            return ContextOverflowException(inner.path("n_prompt_tokens").asInt(), inner.path("n_ctx").asInt())
        }
        return OllamaException("Ollama ответила $status: ${body.take(500)}")
    }

    private fun <T : Any> get(path: String, type: Class<T>): T {
        val response = send(HttpRequest.newBuilder(URI.create("$baseUrl$path")).GET().build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw OllamaException("Ollama ответила ${response.statusCode()} на $path: ${response.body()}")
        return parse(response.body(), type)
    }

    private fun <T> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> = try {
        http.send(request, handler)
    } catch (e: IOException) {
        throw OllamaException("Ollama не отвечает на $baseUrl: ${e.message ?: e.javaClass.simpleName}")
    }

    private fun <T : Any> parse(raw: String, type: Class<T>): T = try {
        json.readValue(raw, type)
    } catch (e: JacksonException) {
        throw OllamaException("Ответ Ollama не разобран: ${raw.take(300)}")
    }
}

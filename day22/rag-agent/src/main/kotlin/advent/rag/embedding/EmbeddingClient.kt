package advent.rag.embedding

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

/** Векторы пачки чанков — по одному на текст, в том же порядке — и сколько токенов прочитала модель. */
data class Embeddings(val vectors: List<FloatArray>, val tokens: Int?)

/** Вектор вопроса и то, как он получен: текст с префиксом, который ушёл в модель, и сам обмен с Ollama. */
data class QueryEmbedding(val input: String, val vector: FloatArray, val tokens: Int?, val exchange: HttpExchange)

/** Ollama недоступна или ответила не так, как ожидалось: виноват не вход, а внешний сервис. */
class EmbeddingException(message: String) : RuntimeException(message)

/**
 * Кубик «текст → вектор» из day21. Префиксы задачи ставит он сам: чанки и вопросы nomic-embed-text
 * кодирует по-разному, и знать об этом нужно только тому, кто с моделью разговаривает.
 * JSON собирается и разбирается здесь же, чтобы в трассировке был ровно тот запрос, что ушёл в Ollama.
 */
@Component
class EmbeddingClient(
    builder: RestClient.Builder,
    private val json: JsonMapper,
    private val properties: OllamaProperties,
) {
    private val ollama: RestClient = builder
        .baseUrl(properties.baseUrl)
        .requestFactory(JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build())
            .apply { setReadTimeout(properties.timeout) })
        .build()

    val model: String get() = properties.model

    fun embedDocuments(texts: List<String>): Embeddings {
        val response = embed(texts.map { properties.documentPrefix + it }).response
        return Embeddings(response.embeddings, response.promptEvalCount)
    }

    fun embedQuery(text: String): QueryEmbedding {
        val input = properties.queryPrefix + text
        val sent = embed(listOf(input))
        return QueryEmbedding(input, sent.response.embeddings.single(), sent.response.promptEvalCount, sent.exchange)
    }

    private fun embed(texts: List<String>): Sent {
        // truncate = false: иначе Ollama молча обрежет длинный текст и вернёт вектор его начала.
        val sent = send(OllamaEmbedRequest(properties.model, texts, truncate = false))
        if (sent.response.embeddings.size != texts.size) {
            throw EmbeddingException("Ollama вернула ${sent.response.embeddings.size} векторов на ${texts.size} текстов")
        }
        return sent
    }

    private fun send(request: OllamaEmbedRequest): Sent {
        val body = json.writeValueAsString(request)
        val started = TimeSource.Monotonic.markNow()
        val response = try {
            ollama.post().uri(EMBED_PATH).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toEntity<String>()
        } catch (e: RestClientResponseException) {
            val reason = e.ollamaError()
            throw when (val status = e.statusCode.value()) {
                // При truncate = false это почти всегда «текст длиннее контекста модели» — ошибка входа, не сервиса.
                400 -> IllegalArgumentException("Ollama отклонила текст: $reason")
                404 -> EmbeddingException(
                    "Ollama не нашла модель ${properties.model}: $reason. Скачайте её: ollama pull ${properties.model}",
                )
                else -> EmbeddingException("Ollama ответила $status: $reason")
            }
        } catch (e: ResourceAccessException) {
            throw EmbeddingException(
                "Нет ответа от Ollama по ${properties.baseUrl} (${(e.cause ?: e).javaClass.simpleName}). " +
                    "Запущен ли сервер? brew services start ollama",
            )
        } catch (e: RestClientException) {
            throw EmbeddingException("Ollama не ответила: ${e.message}")
        }
        val raw = response.body ?: throw EmbeddingException("Ollama вернула пустой ответ")
        val parsed = try {
            json.readValue(raw, OllamaEmbedResponse::class.java)
        } catch (e: JacksonException) {
            throw EmbeddingException("Ответ Ollama не разобран: ${raw.take(300)}")
        }
        val exchange = HttpExchange(
            method = "POST",
            url = properties.baseUrl + EMBED_PATH,
            requestHeaders = mapOf(HttpHeaders.CONTENT_TYPE to MediaType.APPLICATION_JSON_VALUE),
            requestBody = body,
            status = response.statusCode.value(),
            responseBody = raw,
            durationMs = started.elapsedNow().inWholeMilliseconds,
        )
        return Sent(parsed, exchange)
    }

    private fun RestClientResponseException.ollamaError(): String =
        runCatching { getResponseBodyAs(OllamaError::class.java)?.error }.getOrNull()
            ?: responseBodyAsString.take(300)

    private class Sent(val response: OllamaEmbedResponse, val exchange: HttpExchange)

    private companion object {
        const val EMBED_PATH = "/api/embed"
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}

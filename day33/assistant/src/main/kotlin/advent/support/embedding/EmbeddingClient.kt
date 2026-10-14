package advent.support.embedding

import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpClient
import java.time.Duration

class EmbeddingException(message: String) : RuntimeException(message)

@Component
class EmbeddingClient(builder: RestClient.Builder, private val json: JsonMapper, private val properties: OllamaProperties) {
    private val ollama: RestClient = builder
        .baseUrl(properties.baseUrl)
        .requestFactory(JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build())
            .apply { setReadTimeout(properties.timeout) })
        .build()

    val model: String get() = properties.model
    val signature: String get() = listOf(properties.baseUrl, model, properties.documentPrefix, properties.queryPrefix).joinToString("|")

    fun embedDocuments(texts: List<String>): List<FloatArray> = embed(texts.map { properties.documentPrefix + it })

    fun embedQuery(text: String): FloatArray = embed(listOf(properties.queryPrefix + text)).single()

    private fun embed(texts: List<String>): List<FloatArray> {
        // truncate=false запрещает молчаливую потерю конца текста.
        val body = json.writeValueAsString(OllamaEmbedRequest(properties.model, texts, truncate = false))
        val raw = try {
            ollama.post().uri("/api/embed").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String::class.java)
        } catch (e: RestClientResponseException) {
            val reason = runCatching { e.getResponseBodyAs(OllamaError::class.java)?.error }.getOrNull() ?: e.responseBodyAsString.take(300)
            throw if (e.statusCode.value() == 404) EmbeddingException("Ollama не нашла модель ${properties.model}: $reason. Скачайте её: ollama pull ${properties.model}")
            else EmbeddingException("Ollama ответила ${e.statusCode.value()}: $reason")
        } catch (e: ResourceAccessException) {
            throw EmbeddingException("Нет ответа от Ollama по ${properties.baseUrl}. Запущен ли сервер? brew services start ollama")
        } catch (e: RestClientException) {
            throw EmbeddingException("Ollama не ответила: ${e.message}")
        } ?: throw EmbeddingException("Ollama вернула пустой ответ")
        val vectors = json.readValue(raw, OllamaEmbedResponse::class.java).embeddings
        if (vectors.size != texts.size || vectors.any { it.isEmpty() || it.size != vectors.first().size || it.all { value -> value == 0f } }) {
            throw EmbeddingException("Ollama вернула ${vectors.size} векторов на ${texts.size} текстов или пустые векторы")
        }
        return vectors
    }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}

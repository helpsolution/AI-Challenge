package advent.review.embedding

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.boot.context.properties.ConfigurationProperties
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

@ConfigurationProperties("ollama")
data class OllamaProperties(
    val baseUrl: String,
    val model: String,
    val timeout: Duration,
    val documentPrefix: String,
    val queryPrefix: String,
)

class EmbeddingException(message: String) : RuntimeException(message)

internal data class EmbedRequest(val model: String, val input: List<String>, val truncate: Boolean)

internal data class EmbedResponse(val embeddings: List<FloatArray>, @JsonProperty("prompt_eval_count") val promptEvalCount: Int?)

internal data class OllamaError(val error: String)

@Component
class EmbeddingClient(builder: RestClient.Builder, private val json: JsonMapper, private val properties: OllamaProperties) {
    private val ollama: RestClient = builder
        .baseUrl(properties.baseUrl)
        .requestFactory(JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build())
            .apply { setReadTimeout(properties.timeout) })
        .build()

    val model: String get() = properties.model

    fun embedDocuments(texts: List<String>): List<FloatArray> = embed(texts.map { properties.documentPrefix + it })

    fun embedQuery(text: String): FloatArray = embed(listOf(properties.queryPrefix + text)).single()

    private fun embed(texts: List<String>): List<FloatArray> {
        // truncate=false: длинный текст даёт ошибку, а не вектор от его начала.
        val body = json.writeValueAsString(EmbedRequest(properties.model, texts, truncate = false))
        val raw = try {
            ollama.post().uri(EMBED_PATH).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toEntity<String>().body.orEmpty()
        } catch (e: RestClientResponseException) {
            val reason = runCatching { e.getResponseBodyAs(OllamaError::class.java)?.error }.getOrNull() ?: e.responseBodyAsString.take(300)
            throw EmbeddingException("Ollama ответила ${e.statusCode.value()}: $reason")
        } catch (e: ResourceAccessException) {
            throw EmbeddingException("Нет ответа от Ollama по ${properties.baseUrl} (${(e.cause ?: e).javaClass.simpleName})")
        } catch (e: RestClientException) {
            throw EmbeddingException("Ollama не ответила: ${e.message}")
        }
        val vectors = try {
            json.readValue(raw, EmbedResponse::class.java).embeddings
        } catch (e: JacksonException) {
            throw EmbeddingException("Ответ Ollama не разобран: ${raw.take(300)}")
        }
        if (vectors.size != texts.size || vectors.any { it.isEmpty() || it.size != vectors.first().size }) {
            throw EmbeddingException("Ollama вернула ${vectors.size} векторов на ${texts.size} текстов или векторы разной длины")
        }
        return vectors
    }

    private companion object {
        const val EMBED_PATH = "/api/embed"
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}

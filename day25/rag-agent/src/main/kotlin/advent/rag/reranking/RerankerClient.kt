package advent.rag.reranking

import advent.rag.http.HttpExchange
import advent.rag.knowledge.ScoredChunk
import com.fasterxml.jackson.annotation.JsonProperty
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

data class Reranking(val model: String, val ranking: List<ScoredChunk>, val exchange: HttpExchange)

class RerankerException(message: String) : RuntimeException(message)

internal data class RerankRequest(val query: String, val documents: List<String>, @JsonProperty("top_n") val topN: Int)
internal data class RerankResponse(val model: String?, val results: List<RerankResult>)
internal data class RerankResult(val index: Int?, @JsonProperty("relevance_score") val relevanceScore: Double?)

@Component
class RerankerClient(builder: RestClient.Builder, private val json: JsonMapper, properties: RerankerProperties) {
    private val baseUrl = properties.baseUrl.trimEnd('/')
    private val server = builder.baseUrl(baseUrl)
        .requestFactory(JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build())
            .apply { setReadTimeout(properties.timeout) })
        .build()

    fun rerank(question: String, candidates: List<ScoredChunk>): Reranking {
        require(candidates.isNotEmpty()) { "Нет кандидатов для реранкинга" }
        val body = json.writeValueAsString(RerankRequest(question, candidates.map { it.chunk.embeddingText }, candidates.size))
        val started = TimeSource.Monotonic.markNow()
        val response = try {
            server.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toEntity<String>()
        } catch (e: RestClientResponseException) {
            throw RerankerException("Реранкер ответил ${e.statusCode.value()}: ${e.responseBodyAsString.take(300)}")
        } catch (e: ResourceAccessException) {
            throw RerankerException("Нет ответа от реранкера по $baseUrl. Запустите llama-server: команда в README.")
        } catch (e: RestClientException) {
            throw RerankerException("Ошибка запроса к реранкеру: ${e.message}")
        }
        val raw = response.body ?: throw RerankerException("Реранкер вернул пустой ответ")
        val parsed = try {
            json.readValue(raw, RerankResponse::class.java)
        } catch (e: JacksonException) {
            throw RerankerException("Ответ реранкера не разобран: ${raw.take(300)}")
        }
        val results = parsed.results
        if (results.size != candidates.size || results.map { it.index }.toSet() != candidates.indices.toSet() ||
            results.any { it.relevanceScore?.isFinite() != true }) {
            throw RerankerException("Реранкер вернул неполные или некорректные оценки кандидатов")
        }
        val ranking = results.sortedWith(compareByDescending<RerankResult> { it.relevanceScore }.thenBy { it.index })
            .map { candidates[it.index!!].copy(rerankScore = it.relevanceScore) }
        return Reranking(parsed.model ?: "unknown", ranking, HttpExchange(
            "POST", baseUrl + PATH, mapOf(HttpHeaders.CONTENT_TYPE to MediaType.APPLICATION_JSON_VALUE),
            body, response.statusCode.value(), raw, started.elapsedNow().inWholeMilliseconds,
        ))
    }

    private companion object { const val PATH = "/v1/rerank" }
}

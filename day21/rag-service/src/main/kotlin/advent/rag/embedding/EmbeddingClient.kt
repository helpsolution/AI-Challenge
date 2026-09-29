package advent.rag.embedding

import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.body
import kotlin.time.measureTimedValue

/**
 * Кубик «текст → вектор». Ходит в локальную Ollama; модель и адрес задаются только конфигурацией.
 *
 * Эндпоинт — `/api/embed`, а не `/api/embeddings` из лекции: новый принимает сразу пачку текстов,
 * отдаёт векторы уже нормированными (длина 1, поэтому косинус — просто скалярное произведение)
 * и умеет не обрезать длинный текст молча.
 */
@Component
class EmbeddingClient(private val ollama: RestClient, private val properties: OllamaProperties) {

    fun embed(texts: List<String>): Embeddings {
        validate(texts)
        // truncate = false: по умолчанию Ollama обрезает всё, что не влезло в контекст модели, и молча
        // возвращает вектор начала текста. Для индекса это тихая порча данных, лучше получить ошибку.
        val (response, duration) = measureTimedValue {
            send(OllamaEmbedRequest(properties.model, texts, truncate = false))
        }
        if (response.embeddings.size != texts.size) {
            throw EmbeddingException("Ollama вернула ${response.embeddings.size} векторов на ${texts.size} текстов")
        }
        return Embeddings(properties.model, response.embeddings, response.promptEvalCount, duration)
    }

    private fun validate(texts: List<String>) {
        require(texts.isNotEmpty()) { "Нужен хотя бы один текст" }
        texts.forEachIndexed { i, text ->
            // На пустую строку Ollama отвечает 200 и не возвращает вектора — порядок ответов бы съехал.
            require(text.isNotBlank()) { "texts[$i] пустой: у пустого текста нет эмбеддинга" }
        }
    }

    /** Один запрос к Ollama. Её ошибки переводятся в исключения, по которым понятно, что чинить. */
    private fun send(request: OllamaEmbedRequest): OllamaEmbedResponse =
        try {
            ollama.post().uri(EMBED_PATH).body(request).retrieve().body<OllamaEmbedResponse>()
                ?: throw EmbeddingException("Ollama вернула пустой ответ")
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
            throw EmbeddingException("Ответ Ollama не разобран: ${e.message}")
        }

    /** Причина из тела {"error": "..."}; если тело другое, показываем его начало как есть. */
    private fun RestClientResponseException.ollamaError(): String =
        runCatching { getResponseBodyAs(OllamaError::class.java)?.error }.getOrNull()
            ?: responseBodyAsString.take(300)

    private companion object {
        const val EMBED_PATH = "/api/embed"
    }
}

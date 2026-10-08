package advent.rag.llm

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpClient
import java.time.Duration

// installed и loaded — только у локальных: скачана ли модель и лежит ли сейчас в памяти. error — Ollama не ответила.
data class ModelStatus(
    val provider: Provider, val model: String, val installed: Boolean?, val loaded: Boolean?,
    val memoryMb: Long?, val context: Int?, val error: String?,
)

// Модели, которые отвечают на каждый вопрос: сначала локальные в порядке из конфига, затем облако, если есть ключ.
@Component
class Models(builder: RestClient.Builder, json: JsonMapper, localProperties: LocalLlmProperties, cloudProperties: CloudLlmProperties) {

    private val ollama: RestClient = builder.clone()
        .baseUrl(localProperties.baseUrl)
        .requestFactory(JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build())
            .apply { setReadTimeout(localProperties.timeout) })
        .build()

    val local: List<OllamaChatClient> = localProperties.models.map { OllamaChatClient(ollama, json, localProperties, it.trim()) }
    val cloud: DeepSeekClient? = cloudProperties.takeIf { it.apiKey.isNotBlank() }?.let { DeepSeekClient(builder.clone(), json, it) }
    val all: List<ChatModel> = local + listOfNotNull(cloud)

    // Основная локальная модель: на ней же переписывается поисковый запрос, её ответ уходит в историю диалога.
    val main: OllamaChatClient get() = local.first()

    fun status(): List<ModelStatus> {
        val (installed, loaded, error) = try {
            Triple(ollama.get().uri("/api/tags").retrieve().body(OllamaModels::class.java)?.models.orEmpty(),
                ollama.get().uri("/api/ps").retrieve().body(OllamaModels::class.java)?.models.orEmpty(), null)
        } catch (e: RestClientException) {
            Triple(emptyList(), emptyList(), "Ollama не ответила: ${e.message}")
        }
        return local.map { client ->
            val inMemory = loaded.firstOrNull { it.name == client.model }
            ModelStatus(Provider.LOCAL, client.model, installed.any { it.name == client.model }.takeIf { error == null },
                (inMemory != null).takeIf { error == null }, inMemory?.sizeVram?.let { it / 1_048_576 }, inMemory?.contextLength, error)
        } + listOfNotNull(cloud?.let { ModelStatus(Provider.CLOUD, it.model, null, null, null, null, null) })
    }
}

internal data class OllamaModels(val models: List<OllamaModel>)

internal data class OllamaModel(
    val name: String,
    @JsonProperty("size_vram") val sizeVram: Long?,
    @JsonProperty("context_length") val contextLength: Int?,
)

package advent.lab.ollama

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.JsonNode

data class ChatMessage(val role: String, val content: String)

// Пустые поля не отправляются: что не задано, Ollama берёт из модели. Так видно, чем «из коробки» отличается от настроенного.
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    // JSON-схема: Ollama строит из неё грамматику, и модель не может написать ничего вне схемы.
    val format: JsonNode?,
    val options: Map<String, Any>?,
    val think: Boolean?,
    val stream: Boolean = false,
    @JsonProperty("keep_alive") val keepAlive: String = KEEP_ALIVE,
)

// Длительности Ollama отдаёт в наносекундах.
data class ChatResponse(
    val model: String = "",
    val message: ChatMessage? = null,
    @JsonProperty("done_reason") val doneReason: String? = null,
    @JsonProperty("prompt_eval_count") val promptEvalCount: Int? = null,
    @JsonProperty("prompt_eval_cached_count") val promptEvalCachedCount: Int? = null,
    @JsonProperty("prompt_eval_duration") val promptEvalDuration: Long? = null,
    @JsonProperty("eval_count") val evalCount: Int? = null,
    @JsonProperty("eval_duration") val evalDuration: Long? = null,
    @JsonProperty("load_duration") val loadDuration: Long? = null,
    @JsonProperty("total_duration") val totalDuration: Long? = null,
)

// Пустой промпт в /api/generate только загружает модель в память — с теми же options, что и у будущих запросов.
@JsonInclude(JsonInclude.Include.NON_NULL)
data class LoadRequest(
    val model: String,
    val options: Map<String, Any>?,
    @JsonProperty("keep_alive") val keepAlive: String,
    val prompt: String = "",
    val stream: Boolean = false,
)

data class ModelList(val models: List<ModelEntry> = emptyList())

data class ModelEntry(
    val name: String,
    val size: Long = 0,
    val details: ModelDetails? = null,
    // Только у /api/ps: сколько памяти занимает загруженная модель и с каким окном контекста.
    @JsonProperty("size_vram") val sizeVram: Long? = null,
    @JsonProperty("context_length") val contextLength: Int? = null,
)

data class ModelDetails(
    val family: String? = null,
    @JsonProperty("parameter_size") val parameterSize: String? = null,
    @JsonProperty("quantization_level") val quantizationLevel: String? = null,
)

data class ShowResponse(val capabilities: List<String> = emptyList(), val parameters: String? = null)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CreateRequest(
    val model: String,
    val from: String,
    val system: String?,
    val parameters: Map<String, Any>?,
    // Few-shot примеры зашиваются в модель как MESSAGE: Ollama подставляет их перед каждым запросом.
    val messages: List<ChatMessage>?,
    val stream: Boolean = false,
)

data class VersionResponse(val version: String = "")

const val KEEP_ALIVE = "30m"

package advent.rag.llm

import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.JsonNode

internal data class OllamaChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    // JSON-схема: Ollama строит из неё грамматику, и модель физически не может выйти за формат.
    val format: JsonNode,
    val options: OllamaOptions,
    val stream: Boolean = false,
    // Рассуждения перед ответом выключены: для ответа по фрагментам они только добавляют десятки секунд.
    val think: Boolean = false,
)

internal data class OllamaOptions(
    val temperature: Double,
    @JsonProperty("num_predict") val numPredict: Int,
    @JsonProperty("num_ctx") val numCtx: Int,
)

// Длительности Ollama отдаёт в наносекундах.
internal data class OllamaChatResponse(
    val model: String,
    val message: OllamaMessage?,
    @JsonProperty("done_reason") val doneReason: String?,
    @JsonProperty("prompt_eval_count") val promptEvalCount: Int?,
    @JsonProperty("prompt_eval_cached_count") val promptEvalCachedCount: Int?,
    @JsonProperty("prompt_eval_duration") val promptEvalDuration: Long?,
    @JsonProperty("eval_count") val evalCount: Int?,
    @JsonProperty("eval_duration") val evalDuration: Long?,
    @JsonProperty("load_duration") val loadDuration: Long?,
)

internal data class OllamaMessage(val content: String?)

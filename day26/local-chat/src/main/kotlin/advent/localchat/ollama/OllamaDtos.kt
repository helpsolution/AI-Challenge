package advent.localchat.ollama

import com.fasterxml.jackson.annotation.JsonProperty

data class ChatMessage(val role: String, val content: String)

internal data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean,
    val options: Options,
)

internal data class Options(val temperature: Double)

/** Ответ `POST /api/chat` при `stream: false`. Длительности — в наносекундах. */
internal data class ChatResponse(
    val model: String,
    val message: ResponseMessage?,
    @JsonProperty("done_reason") val doneReason: String?,
    @JsonProperty("total_duration") val totalDuration: Long?,
    @JsonProperty("load_duration") val loadDuration: Long?,
    @JsonProperty("prompt_eval_count") val promptEvalCount: Int?,
    @JsonProperty("prompt_eval_duration") val promptEvalDuration: Long?,
    @JsonProperty("eval_count") val evalCount: Int?,
    @JsonProperty("eval_duration") val evalDuration: Long?,
)

internal data class ResponseMessage(val content: String?)

internal data class VersionResponse(val version: String)

internal data class TagsResponse(val models: List<InstalledModel>)

internal data class InstalledModel(val name: String, val size: Long?, val details: ModelDetails?)

internal data class ModelDetails(
    val family: String?,
    @JsonProperty("parameter_size") val parameterSize: String?,
    @JsonProperty("quantization_level") val quantizationLevel: String?,
    @JsonProperty("context_length") val contextLength: Int?,
)

internal data class RunningResponse(val models: List<RunningModel>)

internal data class RunningModel(
    val name: String,
    val size: Long,
    @JsonProperty("size_vram") val sizeVram: Long,
    @JsonProperty("context_length") val contextLength: Int?,
    @JsonProperty("expires_at") val expiresAt: String?,
)

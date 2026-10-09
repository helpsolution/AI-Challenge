package advent.llmservice.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

// Подмножество OpenAI Chat Completions: этого хватает SDK OpenAI, LangChain и любому клиенту с настраиваемым base_url.

data class ChatCompletionRequest(
    // Модель у сервиса одна, поле принимается ради совместимости и не проверяется.
    val model: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val stream: Boolean = false,
    @JsonProperty("max_tokens") val maxTokens: Int? = null,
    @JsonProperty("max_completion_tokens") val maxCompletionTokens: Int? = null,
    val temperature: Double? = null,
)

data class ChatMessage(val role: String, val content: String)

data class ChatCompletion(
    val id: String,
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage,
    val timings: Timings,
) {
    val `object` = "chat.completion"
}

data class Choice(val index: Int, val message: ChatMessage, @JsonProperty("finish_reason") val finishReason: String)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChatCompletionChunk(
    val id: String,
    val created: Long,
    val model: String,
    val choices: List<ChunkChoice>,
    val usage: Usage? = null,
    val timings: Timings? = null,
) {
    val `object` = "chat.completion.chunk"
}

// finish_reason в промежуточных кусках — явный null, как у OpenAI.
@JsonInclude(JsonInclude.Include.ALWAYS)
data class ChunkChoice(val index: Int, val delta: Delta, @JsonProperty("finish_reason") val finishReason: String?)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Delta(val role: String? = null, val content: String? = null)

data class Usage(
    @JsonProperty("prompt_tokens") val promptTokens: Int,
    @JsonProperty("completion_tokens") val completionTokens: Int,
    @JsonProperty("total_tokens") val totalTokens: Int,
)

/** Не из OpenAI: куда ушло время ответа. Готовые клиенты лишнее поле пропускают. */
data class Timings(
    @JsonProperty("queue_ms") val queueMs: Long,
    @JsonProperty("first_token_ms") val firstTokenMs: Long?,
    @JsonProperty("prompt_ms") val promptMs: Long?,
    @JsonProperty("generation_ms") val generationMs: Long?,
    @JsonProperty("tokens_per_second") val tokensPerSecond: Double?,
    @JsonProperty("cached_prompt_tokens") val cachedPromptTokens: Int?,
)

data class ModelList(val data: List<ModelCard>) {
    val `object` = "list"
}

data class ModelCard(val id: String, @JsonProperty("context_window") val contextWindow: Int) {
    val `object` = "model"
    @JsonProperty("owned_by") val ownedBy = "local"
}

data class ErrorBody(val error: ErrorDetail)

data class ErrorDetail(val message: String, val type: String, val code: String)

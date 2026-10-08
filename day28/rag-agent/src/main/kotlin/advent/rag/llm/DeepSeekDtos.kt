package advent.rag.llm

import com.fasterxml.jackson.annotation.JsonProperty

internal data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double,
    val thinking: Map<String, String> = mapOf("type" to "disabled"),
    @JsonProperty("max_tokens") val maxTokens: Int = 2200,
    @JsonProperty("response_format") val responseFormat: Map<String, String>? = null,
)

internal data class ChatResponse(
    val model: String,
    val choices: List<Choice>,
    val usage: Usage?,
)

internal data class Choice(
    val message: ResponseMessage,
    @JsonProperty("finish_reason") val finishReason: String?,
)

internal data class ResponseMessage(val content: String?)

internal data class Usage(
    @JsonProperty("prompt_tokens") val promptTokens: Int,
    @JsonProperty("completion_tokens") val completionTokens: Int,
)

package advent.rag.llm

import com.fasterxml.jackson.annotation.JsonProperty

/** Тело POST /chat/completions — OpenAI-совместимый формат DeepSeek. За пакет не выходит. */
internal data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double,
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

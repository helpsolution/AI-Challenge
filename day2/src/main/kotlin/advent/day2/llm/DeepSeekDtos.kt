package advent.day2.llm

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Тело запроса к /chat/completions. DeepSeek следует OpenAI-совместимой схеме.
 * Все параметры генерации nullable: null означает «не отправлять поле»,
 * то есть использовать значение по умолчанию на стороне провайдера.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ApiMessage>,
    val stream: Boolean = false,
    val temperature: Double? = null,
    @JsonProperty("top_p") val topP: Double? = null,
    @JsonProperty("max_tokens") val maxTokens: Int? = null,
    @JsonProperty("frequency_penalty") val frequencyPenalty: Double? = null,
    @JsonProperty("presence_penalty") val presencePenalty: Double? = null,
    val stop: List<String>? = null,
    /** `{"type":"json_object"}` — режим, в котором провайдер сам гарантирует валидный JSON. */
    @JsonProperty("response_format") val responseFormat: ResponseFormatSpec? = null,
)

data class ResponseFormatSpec(val type: String) {
    companion object {
        val JSON_OBJECT = ResponseFormatSpec("json_object")
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiMessage(
    val role: String,
    val content: String? = null,
    @JsonProperty("reasoning_content") val reasoningContent: String? = null,
)

data class ChatCompletionResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null,
)

data class Choice(
    val index: Int = 0,
    val message: ApiMessage? = null,
    @JsonProperty("finish_reason") val finishReason: String? = null,
)

data class Usage(
    @JsonProperty("prompt_tokens") val promptTokens: Int = 0,
    @JsonProperty("completion_tokens") val completionTokens: Int = 0,
    @JsonProperty("total_tokens") val totalTokens: Int = 0,
)

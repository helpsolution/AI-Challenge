package advent.day6.llm

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Тело запроса к /chat/completions. DeepSeek следует OpenAI-совместимой схеме.
 * Параметры генерации nullable: null означает «не отправлять поле», то есть оставить
 * значение по умолчанию на стороне провайдера.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ApiMessage>,
    val temperature: Double? = null,
    @JsonProperty("max_tokens") val maxTokens: Int? = null,
)

data class ApiMessage(
    val role: String,
    val content: String,
)

data class ChatCompletionResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null,
)

data class Choice(
    val index: Int = 0,
    val message: ResponseMessage? = null,
    @JsonProperty("finish_reason") val finishReason: String? = null,
)

data class ResponseMessage(
    val role: String? = null,
    val content: String? = null,
    /** Скрытое рассуждение deepseek-reasoner. У deepseek-chat поля нет. */
    @JsonProperty("reasoning_content") val reasoningContent: String? = null,
)

data class Usage(
    @JsonProperty("prompt_tokens") val promptTokens: Int = 0,
    @JsonProperty("completion_tokens") val completionTokens: Int = 0,
    @JsonProperty("total_tokens") val totalTokens: Int = 0,
    @JsonProperty("prompt_cache_hit_tokens") val promptCacheHitTokens: Int? = null,
    @JsonProperty("completion_tokens_details") val completionTokensDetails: CompletionTokensDetails? = null,
)

data class CompletionTokensDetails(
    @JsonProperty("reasoning_tokens") val reasoningTokens: Int? = null,
)

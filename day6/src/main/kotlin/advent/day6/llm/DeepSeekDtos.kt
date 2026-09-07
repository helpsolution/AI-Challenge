package advent.day6.llm

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Тело запроса к /chat/completions. DeepSeek следует OpenAI-совместимой схеме.
 * Ответ всегда запрашивается потоком, а `stream_options.include_usage` заставляет провайдера
 * прислать расход токенов последним чанком — без этого в стриме его просто нет.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ApiMessage>,
    val temperature: Double? = null,
    @JsonProperty("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean = true,
    @JsonProperty("stream_options") val streamOptions: StreamOptions? = StreamOptions(),
)

data class StreamOptions(
    @JsonProperty("include_usage") val includeUsage: Boolean = true,
)

data class ApiMessage(
    val role: String,
    val content: String,
)

/** Один чанк server-sent events: дельта текста и, в самом конце, расход токенов. */
data class StreamChunk(
    val id: String? = null,
    val model: String? = null,
    val choices: List<StreamChoice> = emptyList(),
    val usage: Usage? = null,
)

data class StreamChoice(
    val index: Int = 0,
    val delta: Delta? = null,
    @JsonProperty("finish_reason") val finishReason: String? = null,
)

data class Delta(
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

package advent.day5.llm

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Тело запроса к /chat/completions. OpenRouter следует OpenAI-совместимой схеме,
 * той же, что и DeepSeek в днях 1–4, поэтому DTO переехали почти дословно.
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
    val stop: List<String>? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiMessage(
    val role: String,
    val content: String? = null,
    /** Цепочка рассуждения у reasoning-моделей. Приходит отдельно от ответа. */
    val reasoning: String? = null,
)

/**
 * Ответ провайдера. Помечен [JsonIgnoreProperties]: у каждой модели в ответе
 * своя россыпь необязательных полей, и падать на незнакомом поле нельзя —
 * весь смысл дня в том, чтобы опрашивать модели разных вендоров одним кодом.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatCompletionResponse(
    val id: String? = null,
    val model: String? = null,
    /** Кто на самом деле обслужил запрос: OpenRouter маршрутизирует на разные площадки. */
    val provider: String? = null,
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null,
    /** OpenRouter умеет отдавать ошибку кодом 200 и телом с этим полем. */
    val error: ProviderError? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProviderError(
    val code: Int? = null,
    val message: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Choice(
    val index: Int = 0,
    val message: ApiMessage? = null,
    @JsonProperty("finish_reason") val finishReason: String? = null,
    /** Причина остановки словами самого вендора, до нормализации OpenRouter. */
    @JsonProperty("native_finish_reason") val nativeFinishReason: String? = null,
)

/**
 * Расход по запросу. Сверх обычных счётчиков токенов OpenRouter кладёт сюда `cost` —
 * сумму, реально списанную с аккаунта. Это и есть ответ на «замерьте стоимость»:
 * цифра от провайдера, а не наше умножение токенов на прайс-лист.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Usage(
    @JsonProperty("prompt_tokens") val promptTokens: Int = 0,
    @JsonProperty("completion_tokens") val completionTokens: Int = 0,
    @JsonProperty("total_tokens") val totalTokens: Int = 0,
    /** Списано в долларах. У бесплатных моделей — ноль. */
    val cost: Double? = null,
    @JsonProperty("prompt_tokens_details") val promptTokensDetails: PromptTokensDetails? = null,
    @JsonProperty("completion_tokens_details") val completionTokensDetails: CompletionTokensDetails? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PromptTokensDetails(
    @JsonProperty("cached_tokens") val cachedTokens: Int = 0,
)

/**
 * Скрытая часть расхода: reasoning-модели тратят токены на размышление,
 * они оплачиваются как выходные, но в тексте ответа их не видно.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CompletionTokensDetails(
    @JsonProperty("reasoning_tokens") val reasoningTokens: Int = 0,
)

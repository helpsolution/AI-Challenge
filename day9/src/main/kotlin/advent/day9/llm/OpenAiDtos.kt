package advent.day9.llm

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Схема `/chat/completions`. Одна на двух провайдеров: и DeepSeek, и OpenRouter следуют
 * OpenAI-совместимому формату, поэтому DTO общие, а клиенты разные.
 *
 * Параметры генерации nullable: null означает «не отправлять поле», то есть оставить
 * значение по умолчанию на стороне провайдера.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ApiMessage>,
    val temperature: Double? = null,
    @JsonProperty("max_tokens") val maxTokens: Int? = null,
    /**
     * Расширение OpenRouter: чем агрегатор обрабатывает запрос перед отправкой в модель.
     * Нужно ровно для одного — управлять сжатием контекста, которое иначе включается само
     * и молча вырезает середину истории. DeepSeek такого поля не знает, ему оно и не уходит.
     */
    val plugins: List<RequestPlugin>? = null,
)

data class RequestPlugin(
    val id: String,
    val enabled: Boolean,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiMessage(
    val role: String,
    val content: String,
)

/**
 * Ответ провайдера. Помечен [JsonIgnoreProperties]: у каждого провайдера в ответе своя
 * россыпь необязательных полей, и падать на незнакомом поле нельзя — весь смысл общей
 * схемы в том, чтобы одним кодом опрашивать двоих.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatCompletionResponse(
    val id: String? = null,
    val model: String? = null,
    /** Кто на самом деле обслужил запрос. Заполняет OpenRouter: он маршрутизирует на площадки. */
    val provider: String? = null,
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null,
    /** OpenRouter умеет отдать ошибку кодом 200 и телом с этим полем. */
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
    val message: ResponseMessage? = null,
    @JsonProperty("finish_reason") val finishReason: String? = null,
    /** Причина остановки словами самого вендора, до нормализации агрегатором. */
    @JsonProperty("native_finish_reason") val nativeFinishReason: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ResponseMessage(
    val role: String? = null,
    val content: String? = null,
    /** Скрытое рассуждение reasoning-моделей. У обычных моделей поля нет. */
    @JsonProperty("reasoning_content") val reasoningContent: String? = null,
    val reasoning: String? = null,
)

/**
 * Расход по запросу — сердце этого дня.
 *
 * Общие поля считают оба провайдера. Дальше начинаются различия, и они существенные:
 * OpenRouter кладёт в `cost` реально списанную сумму, а кэш показывает в
 * `prompt_tokens_details.cached_tokens`; DeepSeek денег не сообщает вовсе, а кэш
 * отдаёт плоским полем `prompt_cache_hit_tokens`. Здесь лежит объединение, а каждый
 * клиент забирает из него своё.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Usage(
    @JsonProperty("prompt_tokens") val promptTokens: Int = 0,
    @JsonProperty("completion_tokens") val completionTokens: Int = 0,
    @JsonProperty("total_tokens") val totalTokens: Int = 0,
    /** Списано в долларах. Заполняет OpenRouter; у бесплатных моделей — ноль. */
    val cost: Double? = null,
    @JsonProperty("prompt_cache_hit_tokens") val promptCacheHitTokens: Int? = null,
    @JsonProperty("prompt_tokens_details") val promptTokensDetails: PromptTokensDetails? = null,
    @JsonProperty("completion_tokens_details") val completionTokensDetails: CompletionTokensDetails? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PromptTokensDetails(
    @JsonProperty("cached_tokens") val cachedTokens: Int = 0,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CompletionTokensDetails(
    @JsonProperty("reasoning_tokens") val reasoningTokens: Int = 0,
)

package advent.day10.llm

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Схема `/chat/completions` в том виде, в каком её понимает DeepSeek.
 *
 * От дня 8 отличается тем, чего здесь нет: провайдер один, поэтому ушли поля, которые
 * существовали только ради OpenRouter, — `plugins`, `provider`, `native_finish_reason`,
 * `cost` и ошибка в теле успешного ответа.
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
     * Режим ответа. `{"type": "json_object"}` заставляет DeepSeek вернуть синтаксически
     * корректный JSON.
     *
     * Нужно ровно одному месту — извлечению фактов. Разбирать JSON, выковырянный
     * регулярками из обычного ответа с ```-обрамлением, значит встроить в память агента
     * второй источник отказов; режим провайдера убирает его целиком.
     */
    @JsonProperty("response_format") val responseFormat: ResponseFormat? = null,
)

data class ResponseFormat(val type: String) {
    companion object {
        val JSON = ResponseFormat("json_object")
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiMessage(
    val role: String,
    val content: String,
)

/**
 * Ответ провайдера. Помечен [JsonIgnoreProperties] не из перестраховки: DeepSeek
 * добавляет необязательные поля молча, и падать на незнакомом поле из-за этого нельзя.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatCompletionResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Choice(
    val index: Int = 0,
    val message: ResponseMessage? = null,
    @JsonProperty("finish_reason") val finishReason: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ResponseMessage(
    val role: String? = null,
    val content: String? = null,
    /** Скрытое рассуждение reasoning-моделей. У обычных моделей поля нет. */
    @JsonProperty("reasoning_content") val reasoningContent: String? = null,
)

/**
 * Расход по запросу.
 *
 * Ключевое поле для этого дня — `prompt_cache_hit_tokens`: DeepSeek кэширует неизменный
 * префикс промпта и берёт за него в разы меньше. Это прямо влияет на сравнение стратегий,
 * потому что скользящее окно префикс ломает каждый ход, а полная история — нет.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Usage(
    @JsonProperty("prompt_tokens") val promptTokens: Int = 0,
    @JsonProperty("completion_tokens") val completionTokens: Int = 0,
    @JsonProperty("total_tokens") val totalTokens: Int = 0,
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

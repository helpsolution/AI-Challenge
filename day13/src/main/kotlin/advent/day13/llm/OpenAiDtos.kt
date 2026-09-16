package advent.day13.llm

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * OpenAI-совместимая схема `/chat/completions`, которую принимает OpenRouter.
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
     * Режим ответа. `{"type": "json_object"}` заставляет модель вернуть синтаксически
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
 * Ответ OpenRouter. Неизвестные поля игнорируются, поскольку площадки добавляют свои метаданные.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatCompletionResponse(
    val id: String? = null,
    val model: String? = null,
    val provider: String? = null,
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null,
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
 * Расход по запросу.
 *
 * OpenRouter возвращает фактическую стоимость и счетчики токенов площадки.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Usage(
    @JsonProperty("prompt_tokens") val promptTokens: Int = 0,
    @JsonProperty("completion_tokens") val completionTokens: Int = 0,
    @JsonProperty("total_tokens") val totalTokens: Int = 0,
    val cost: Double? = null,
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

package advent.posts.llm

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * OpenAI-совместимая схема `/chat/completions`, которую принимает DeepSeek.
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
     * корректный JSON, поэтому ответ не приходится выковыривать регулярками из текста.
     */
    @JsonProperty("response_format") val responseFormat: ResponseFormat? = null,
    /** Редактору не нужен скрытый ход мысли: он удлиняет ожидание и не попадает в ответ. */
    val thinking: Map<String, String>? = mapOf("type" to "disabled"),
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

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatCompletionResponse(
    val model: String? = null,
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Choice(
    val message: ResponseMessage? = null,
    @JsonProperty("finish_reason") val finishReason: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ResponseMessage(
    val content: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Usage(
    @JsonProperty("prompt_tokens") val promptTokens: Int = 0,
    @JsonProperty("completion_tokens") val completionTokens: Int = 0,
    @JsonProperty("prompt_cache_hit_tokens") val promptCacheHitTokens: Int = 0,
)

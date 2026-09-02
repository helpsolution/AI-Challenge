package advent.day3.web

import advent.day3.llm.LlmExchange
import advent.day3.llm.Usage
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

/**
 * Параметры генерации. Каждый nullable: null = «не трогаем, берём дефолт провайдера».
 * Границы соответствуют документации DeepSeek — заведомо невалидное отсекаем
 * до похода в сеть, чтобы не тратить запросы и не получать 422.
 */
data class LlmParams(
    @field:DecimalMin(value = "0.0", message = "temperature: минимум 0.0")
    @field:DecimalMax(value = "2.0", message = "temperature: максимум 2.0")
    val temperature: Double? = null,

    @field:Min(value = 1, message = "max_tokens: минимум 1")
    @field:Max(value = 8_192, message = "max_tokens: максимум 8192")
    val maxTokens: Int? = null,
)

data class UsageView(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
)

fun Usage.toView() = UsageView(promptTokens, completionTokens, totalTokens)

/** Сырой обмен с провайдером в том виде, в каком его показывает интерфейс. */
data class ExchangeView(
    val url: String,
    val method: String,
    val requestHeaders: Map<String, String>,
    val requestBody: String,
    val status: Int? = null,
    val responseBody: String? = null,
)

fun LlmExchange.toView() = ExchangeView(
    url = url,
    method = method,
    requestHeaders = requestHeaders,
    requestBody = requestBody,
    status = status,
    responseBody = responseBody,
)

/** Описание доступных моделей — фронт строит по нему выпадающий список. */
data class ModelsResponse(
    val models: List<String>,
    val defaultModel: String,
)

data class ErrorResponse(
    val error: String,
    val details: List<String> = emptyList(),
    /** Для ошибок провайдера — тот же сырой обмен, что и при успехе. */
    val exchange: ExchangeView? = null,
)

package advent.day1.web

import advent.day1.llm.LlmExchange

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Запрос из веб-интерфейса. [params] опциональны: если продвинутый режим выключен,
 * поле не приходит вовсе и модель работает на значениях по умолчанию.
 */
data class ChatRequest(
    @field:NotBlank(message = "Введите запрос")
    @field:Size(max = 8_000, message = "Запрос длиннее 8000 символов")
    val prompt: String,

    val model: String? = null,

    @field:Valid
    val params: LlmParams? = null,
)

/**
 * Параметры генерации. Каждый nullable: null = «не трогаем, берём дефолт провайдера».
 * Границы соответствуют документации DeepSeek — отсекаем заведомо невалидное
 * до похода в сеть, чтобы не тратить запросы и не получать 422.
 */
data class LlmParams(
    @field:DecimalMin(value = "0.0", message = "temperature: минимум 0.0")
    @field:DecimalMax(value = "2.0", message = "temperature: максимум 2.0")
    val temperature: Double? = null,

    @field:DecimalMin(value = "0.0", message = "top_p: минимум 0.0")
    @field:DecimalMax(value = "1.0", message = "top_p: максимум 1.0")
    val topP: Double? = null,

    @field:Min(value = 1, message = "max_tokens: минимум 1")
    @field:Max(value = 8_192, message = "max_tokens: максимум 8192")
    val maxTokens: Int? = null,

    @field:DecimalMin(value = "-2.0", message = "frequency_penalty: минимум -2.0")
    @field:DecimalMax(value = "2.0", message = "frequency_penalty: максимум 2.0")
    val frequencyPenalty: Double? = null,

    @field:DecimalMin(value = "-2.0", message = "presence_penalty: минимум -2.0")
    @field:DecimalMax(value = "2.0", message = "presence_penalty: максимум 2.0")
    val presencePenalty: Double? = null,

    @field:Size(max = 16, message = "stop: не более 16 последовательностей")
    val stop: List<String>? = null,
)

data class ChatResponse(
    val answer: String,
    /** Цепочка рассуждений — заполняется только моделью deepseek-reasoner. */
    val reasoning: String? = null,
    val model: String,
    val finishReason: String? = null,
    val usage: UsageView? = null,
    val latencyMs: Long,
    /** Что фактически ушло в API — чтобы в UI было видно, какие параметры применились. */
    val appliedParams: Map<String, Any> = emptyMap(),
    /** Настоящий HTTP-обмен с провайдером — показывается в интерфейсе как есть. */
    val exchange: ExchangeView? = null,
)

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

data class UsageView(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
)

/** Описание модели и границ параметров — фронт строит по нему форму продвинутого режима. */
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

package advent.day4.web

import advent.day4.llm.LlmExchange
import advent.day4.llm.Usage

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

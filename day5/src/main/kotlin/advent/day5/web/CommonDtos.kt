package advent.day5.web

import advent.day5.catalog.ModelCard
import advent.day5.catalog.ModelTier
import advent.day5.llm.LlmExchange
import advent.day5.llm.Usage

/**
 * Расход токенов по одному запросу. Reasoning-токены вынесены отдельно:
 * они входят в completion и оплачиваются как выходные, но в тексте ответа их нет —
 * без этой строки счёт у сильной модели выглядит необъяснимо большим.
 */
data class UsageView(
    val promptTokens: Int,
    val completionTokens: Int,
    val reasoningTokens: Int,
    val cachedTokens: Int,
    val totalTokens: Int,
)

fun Usage.toView() = UsageView(
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    reasoningTokens = completionTokensDetails?.reasoningTokens ?: 0,
    cachedTokens = promptTokensDetails?.cachedTokens ?: 0,
    totalTokens = totalTokens,
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

/** Карточка модели для интерфейса: имя, цена, ссылки. */
data class ModelCardView(
    val id: String,
    val tier: ModelTier,
    val title: String,
    val scale: String,
    val default: Boolean,
    val promptPricePerMillion: Double? = null,
    val completionPricePerMillion: Double? = null,
    val contextLength: Int? = null,
    val openRouterUrl: String,
    val huggingFaceUrl: String? = null,
)

fun ModelCard.toView() = ModelCardView(
    id = id,
    tier = tier,
    title = title,
    scale = scale,
    default = default,
    promptPricePerMillion = promptPricePerMillion,
    completionPricePerMillion = completionPricePerMillion,
    contextLength = contextLength,
    openRouterUrl = openRouterUrl,
    huggingFaceUrl = huggingFaceUrl,
)

/** Справочник уровней — интерфейс строит по нему подписи и подсказки. */
data class TierInfo(
    val id: ModelTier,
    val title: String,
    val emoji: String,
    val summary: String,
    val goodFor: List<String>,
    val weakAt: List<String>,
)

data class CatalogResponse(
    val tiers: List<TierInfo>,
    val models: List<ModelCardView>,
    /** true, если прайс провайдера получить не удалось: цены в карточках будут пустыми. */
    val pricingUnavailable: Boolean = false,
)

data class ErrorResponse(
    val error: String,
    val details: List<String> = emptyList(),
    /** Для ошибок провайдера — тот же сырой обмен, что и при успехе. */
    val exchange: ExchangeView? = null,
)

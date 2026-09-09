package advent.day8.llm

import advent.day8.config.DeepSeekProperties
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import java.net.URI

/**
 * Клиент DeepSeek — провайдер дней 1–7 и штатный для этого проекта.
 *
 * По части токенов у него две особенности. Первая: кэш префикса он показывает плоским
 * полем `prompt_cache_hit_tokens`, и это не мелочь — растущая неизменная часть истории
 * попадает в кэш, поэтому повторная отправка переписки стоит в десятки раз дешевле,
 * чем кажется по числу токенов. Вторая: денег DeepSeek не сообщает вообще, поэтому цену
 * считаем сами по прайсу из конфига и честно помечаем как оценку — прайс лежит в чужих
 * руках и меняется, а тариф вне пиковых часов ещё и вдвое ниже.
 */
class DeepSeekClient(
    private val properties: DeepSeekProperties,
    private val objectMapper: ObjectMapper,
    private val http: HttpJson = HttpJson(properties.connectTimeout),
) : LlmClient {
    private val log = LoggerFactory.getLogger(javaClass)

    private val endpoint: URI = URI.create(properties.baseUrl.trimEnd('/') + COMPLETIONS_PATH)

    override fun complete(request: ChatCompletionRequest): LlmCompletion {
        if (properties.apiKey.isBlank()) {
            throw LlmException("Не задан API-ключ: переменная окружения DEEPSEEK_API_KEY пуста")
        }

        val response = http.post(
            endpoint = endpoint,
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "Authorization" to "Bearer ${properties.apiKey}",
            ),
            body = objectMapper.writeValueAsString(request),
            readTimeout = properties.readTimeout,
            log = log,
        )

        if (response.status >= 400) {
            log.warn("DeepSeek ответил ошибкой {}: {}", response.status, response.body.take(500))
            throw LlmException(
                message = errorMessage(response.status, response.body),
                providerStatus = response.status,
                providerBody = response.body,
            )
        }

        val parsed = objectMapper.readValue(response.body, ChatCompletionResponse::class.java)
        val choice = parsed.choices.firstOrNull()
            ?: throw LlmException("DeepSeek не вернул ни одного варианта ответа", providerBody = response.body)
        val answer = choice.message?.content?.takeIf { it.isNotBlank() }
            ?: throw LlmException("DeepSeek вернул ответ без текста", providerBody = response.body)

        return LlmCompletion(
            content = answer.trim(),
            reasoning = choice.message.reasoningContent?.takeIf { it.isNotBlank() },
            model = parsed.model,
            provider = "deepseek",
            finishReason = choice.finishReason,
            usage = parsed.usage?.toTokenUsage(),
        )
    }

    /**
     * Цена хода по прайсу из конфига. Считается в три ставки, потому что вход делится
     * на две части с разной ценой: то, что зачлось из кэша, и то, что модель читала заново.
     */
    private fun Usage.toTokenUsage(): TokenUsage {
        val cached = promptCacheHitTokens ?: promptTokensDetails?.cachedTokens ?: 0
        val prices = properties.prices
        val cost = if (prices.isSet) {
            val fresh = (promptTokens - cached).coerceAtLeast(0)
            (fresh * prices.inputCacheMiss + cached * prices.inputCacheHit + completionTokens * prices.output) / 1_000_000
        } else {
            null
        }

        return TokenUsage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
            cachedPromptTokens = cached,
            reasoningTokens = completionTokensDetails?.reasoningTokens ?: 0,
            costUsd = cost,
            costSource = if (cost == null) CostSource.UNKNOWN else CostSource.PRICE_LIST,
        )
    }

    /**
     * Текст ошибки провайдера доходит до интерфейса дословно: в этот день именно в нём
     * написано, каков лимит контекста и на сколько мы его перебрали.
     */
    private fun errorMessage(status: Int, body: String): String {
        val detail = extractMessage(body)
        return when (status) {
            401 -> "DeepSeek отклонил ключ (401). Проверьте DEEPSEEK_API_KEY."
            402 -> "Недостаточно средств на балансе DeepSeek (402)."
            422 -> "DeepSeek отклонил параметры запроса (422)."
            429 -> "Превышен лимит запросов к DeepSeek (429). Попробуйте позже."
            in 500..599 -> "DeepSeek временно недоступен ($status). Попробуйте позже."
            else -> "DeepSeek вернул ошибку $status."
        }.let { if (detail == null) it else "$it $detail" }
    }

    private fun extractMessage(body: String): String? = runCatching {
        objectMapper.readTree(body)["error"]?.get("message")?.asString()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private companion object {
        const val COMPLETIONS_PATH = "/chat/completions"
    }
}

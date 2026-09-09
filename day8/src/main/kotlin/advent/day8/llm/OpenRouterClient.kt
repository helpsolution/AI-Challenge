package advent.day8.llm

import advent.day8.config.OpenRouterProperties
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import java.net.URI

/**
 * Клиент OpenRouter — второй провайдер, добавленный ради темы дня.
 *
 * Он здесь не вместо DeepSeek, а рядом: у всех трёх моделей DeepSeek контекст 1M токенов,
 * и упереться в него живым диалогом — это часы ожидания. У OpenRouter же есть модели
 * с контекстом 4K, и на них переполнение достигается за десяток ходов. Протокол тот же
 * OpenAI-совместимый, поэтому агент разницы не замечает.
 *
 * Два его свойства прямо по теме:
 *
 * 1. В `usage.cost` приходит сумма, реально списанная с аккаунта. Это ответ на «покажите
 *    стоимость» цифрой от провайдера, а не нашим умножением токенов на прайс.
 *
 * 2. Для моделей с контекстом 8K и меньше OpenRouter **по умолчанию** включает сжатие
 *    контекста: молча вырезает середину промпта, чтобы тот влез в лимит. Запрос при этом
 *    отвечает `200` и `finish_reason: stop`, ошибки нет никакой — агент просто теряет
 *    середину разговора. Проверено живьём: из 21190 отправленных токенов до модели дошло
 *    731, то есть 3% истории. Поэтому сжатие здесь — явный переключатель в конфиге, а не
 *    молчаливое поведение по умолчанию: с `context-compression: false` тот же запрос
 *    честно падает с `400` и текстом про лимит.
 */
class OpenRouterClient(
    private val properties: OpenRouterProperties,
    private val objectMapper: ObjectMapper,
    private val http: HttpJson = HttpJson(properties.connectTimeout),
) : LlmClient {
    private val log = LoggerFactory.getLogger(javaClass)

    private val endpoint: URI = URI.create(properties.baseUrl.trimEnd('/') + COMPLETIONS_PATH)

    override fun complete(request: ChatCompletionRequest): LlmCompletion {
        if (properties.apiKey.isBlank()) {
            throw LlmException("Не задан API-ключ: переменная окружения OPENROUTER_API_KEY пуста")
        }

        val body = objectMapper.writeValueAsString(request.withCompressionSetting())
        val response = http.post(
            endpoint = endpoint,
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "Authorization" to "Bearer ${properties.apiKey}",
                // Заголовки атрибуции: по ним OpenRouter показывает источник трафика.
                "HTTP-Referer" to properties.referer,
                "X-Title" to properties.title,
            ),
            body = body,
            readTimeout = properties.readTimeout,
            log = log,
        )

        if (response.status >= 400) {
            log.warn("OpenRouter ответил ошибкой {}: {}", response.status, response.body.take(500))
            throw LlmException(
                message = errorMessage(response.status, response.body),
                providerStatus = response.status,
                providerBody = response.body,
            )
        }

        val parsed = objectMapper.readValue(response.body, ChatCompletionResponse::class.java)

        // OpenRouter умеет вернуть 200 и ошибку в теле — например, когда площадка под
        // моделью отказала. Без этой проверки пользователь увидел бы невнятное
        // «провайдер не вернул ни одного варианта».
        parsed.error?.let {
            throw LlmException(
                message = "OpenRouter отказал: ${it.message ?: "причина не указана"}",
                providerStatus = it.code,
                providerBody = response.body,
            )
        }

        val choice = parsed.choices.firstOrNull()
            ?: throw LlmException("OpenRouter не вернул ни одного варианта ответа", providerBody = response.body)
        val answer = choice.message?.content?.takeIf { it.isNotBlank() }
            ?: throw LlmException("OpenRouter вернул ответ без текста", providerBody = response.body)

        return LlmCompletion(
            content = answer.trim(),
            reasoning = choice.message.reasoning?.takeIf { it.isNotBlank() },
            model = parsed.model,
            provider = parsed.provider ?: "openrouter",
            finishReason = choice.finishReason ?: choice.nativeFinishReason,
            usage = parsed.usage?.toTokenUsage(),
        )
    }

    /**
     * Сжатие контекста задаём всегда явно, в обе стороны.
     *
     * Не полагаемся на поведение по умолчанию не из недоверия, а потому что оно зависит
     * от размера контекста конкретной модели: до 8K включительно сжатие включается само,
     * выше — нет. Молчаливое «иногда режем историю, иногда падаем» — ровно та неясность,
     * которую этот день должен показать, а не унаследовать.
     */
    private fun ChatCompletionRequest.withCompressionSetting() = copy(
        plugins = listOf(RequestPlugin(id = CONTEXT_COMPRESSION, enabled = properties.contextCompression)),
    )

    /** Здесь `cost` — факт, а не оценка: столько списано с баланса. */
    private fun Usage.toTokenUsage() = TokenUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        cachedPromptTokens = promptTokensDetails?.cachedTokens ?: 0,
        reasoningTokens = completionTokensDetails?.reasoningTokens ?: 0,
        costUsd = cost,
        costSource = if (cost == null) CostSource.UNKNOWN else CostSource.PROVIDER,
    )

    /**
     * Своей формулировкой ошибку не подменяем, а дополняем.
     *
     * Сообщение OpenRouter про превышение контекста — самое ценное, что приходит в этот
     * день: в нём и лимит модели, и сколько мы запросили, и раздельно вход с выходом.
     * Такое надо показывать дословно.
     */
    private fun errorMessage(status: Int, body: String): String {
        val detail = extractMessage(body)
        val prefix = when (status) {
            400 -> "OpenRouter отклонил запрос (400)."
            401 -> "OpenRouter отклонил ключ (401). Проверьте OPENROUTER_API_KEY."
            402 -> "Недостаточно средств на балансе OpenRouter (402). Пополните счёт."
            403 -> "Запрос отклонён модерацией провайдера (403)."
            404 -> "Такой модели у провайдера нет (404)."
            408 -> "Модель не ответила за отведённое время (408)."
            429 -> "Превышен лимит запросов (429)."
            502 -> "Площадка под моделью недоступна (502)."
            503 -> "Нет доступной площадки под эту модель (503)."
            in 500..599 -> "OpenRouter временно недоступен ($status)."
            else -> "OpenRouter вернул ошибку $status."
        }
        return if (detail == null) prefix else "$prefix $detail"
    }

    private fun extractMessage(body: String): String? = runCatching {
        objectMapper.readTree(body)["error"]?.get("message")?.asString()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private companion object {
        const val COMPLETIONS_PATH = "/chat/completions"
        const val CONTEXT_COMPRESSION = "context-compression"
    }
}

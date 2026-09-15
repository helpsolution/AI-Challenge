package advent.day11.llm

/**
 * Контракт обращения к LLM: запрос внутрь, разобранный ответ наружу.
 *
 * Реализация одна - [OpenRouterClient]. Интерфейс тем не менее остается: за ним удобно
 * подменять модель заглушкой и держать агента независимым от конкретного провайдера.
 */
fun interface LlmClient {
    fun complete(request: ChatCompletionRequest): LlmCompletion
}

/** Итог одного обращения: текст ответа, кто ответил, почему остановился и во что это обошлось. */
data class LlmCompletion(
    val content: String,
    /** Скрытый ход мысли reasoning-модели. В тексте ответа его нет. */
    val reasoning: String? = null,
    val model: String? = null,
    val provider: String? = null,
    val finishReason: String? = null,
    val usage: TokenUsage? = null,
)

/**
 * Расход одного обращения — в терминах приложения, а не провайдера.
 *
 * [promptTokens] — не наша оценка, а число, которое вернул провайдер.
 */
data class TokenUsage(
    /** Весь вход: persona + memory layers + short-term context + новый вопрос. */
    val promptTokens: Int,
    /** Ответ модели. */
    val completionTokens: Int,
    val totalTokens: Int,
    /**
     * Часть входа, зачтённая из кэша префикса. Стоит примерно в тридцать раз дешевле
     * остального.
     */
    val cachedPromptTokens: Int = 0,
    /** Размышление reasoning-модели: оплачивается как выход, но в тексте ответа его нет. */
    val reasoningTokens: Int = 0,
    val costUsd: Double? = null,
    val costSource: CostSource = CostSource.UNKNOWN,
)

/**
 * Откуда взялась цена — это важно различать, а не прятать за одним числом.
 *
 * [PROVIDER] - фактическое списание, которое вернул OpenRouter.
 * [PRICE_LIST] - наша оценка по конфигурации.
 * [UNKNOWN] - цены нет.
 */
enum class CostSource { PROVIDER, PRICE_LIST, UNKNOWN }

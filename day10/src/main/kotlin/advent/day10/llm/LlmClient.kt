package advent.day10.llm

/**
 * Контракт обращения к LLM: запрос внутрь, разобранный ответ наружу.
 *
 * Реализация одна — [DeepSeekClient]. Интерфейс тем не менее остаётся, и не ради
 * «вдруг появится второй провайдер»: за ним удобно подменять модель заглушкой при
 * прогоне сценария, а стратегия Facts будет ходить в модель отдельно от агента —
 * ей нужен тот же контракт, а не конкретный клиент.
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
    val finishReason: String? = null,
    val usage: TokenUsage? = null,
)

/**
 * Расход одного обращения — в терминах приложения, а не провайдера.
 *
 * [promptTokens] — не наша оценка, а число, за которое выставлен счёт. Именно поэтому
 * по нему можно сравнивать стратегии: «окно дешевле полной истории» — утверждение
 * проверяемое, а не правдоподобное.
 */
data class TokenUsage(
    /** Весь вход: персона + то, что собрала стратегия + новый вопрос. */
    val promptTokens: Int,
    /** Ответ модели. */
    val completionTokens: Int,
    val totalTokens: Int,
    /**
     * Часть входа, зачтённая из кэша префикса. Стоит примерно в тридцать раз дешевле
     * остального — и это ломает наивное «меньше токенов значит дешевле».
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
 * [PRICE_LIST] — наше умножение токенов на прайс из конфига. Оценка: прайс может
 * устареть, а у DeepSeek ещё и различаются пиковый и ночной тарифы.
 * [UNKNOWN] — прайс не задан, цены нет вовсе.
 */
enum class CostSource { PRICE_LIST, UNKNOWN }

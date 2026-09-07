package advent.day6.llm

/**
 * Контракт обращения к LLM. Ответ приходит потоком: [onDelta] вызывается на каждый кусочек
 * по мере генерации, а возвращаемое значение — уже собранный итог с расходом токенов.
 * Провайдера можно заменить, не трогая агента.
 */
fun interface LlmClient {
    fun stream(request: ChatCompletionRequest, onDelta: (LlmDelta) -> Unit): LlmCompletion
}

/** Кусочек ответа: текст для пользователя и/или скрытое рассуждение (у reasoning-моделей). */
data class LlmDelta(
    val content: String? = null,
    val reasoning: String? = null,
)

/** Итог одного обращения: полный текст, модель, причина остановки и расход токенов. */
data class LlmCompletion(
    val content: String,
    val reasoning: String? = null,
    val model: String? = null,
    val finishReason: String? = null,
    val usage: Usage? = null,
)

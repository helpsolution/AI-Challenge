package advent.day7.llm

/**
 * Контракт обращения к LLM: запрос внутрь, разобранный ответ наружу.
 * Провайдера можно заменить, не трогая агента.
 */
fun interface LlmClient {
    fun complete(request: ChatCompletionRequest): LlmCompletion
}

/** Итог одного обращения: текст ответа, модель, причина остановки и расход токенов. */
data class LlmCompletion(
    val content: String,
    /** Скрытый ход мысли reasoning-модели. В тексте ответа его нет. */
    val reasoning: String? = null,
    val model: String? = null,
    val finishReason: String? = null,
    val usage: Usage? = null,
)

package advent.posts.llm

/**
 * Контракт обращения к LLM: запрос внутрь, разобранный ответ наружу.
 *
 * Реализация одна - [DeepSeekClient]. Интерфейс тем не менее остается: за ним удобно
 * подменять модель заглушкой и держать агента независимым от конкретного провайдера.
 */
fun interface LlmClient {
    fun complete(request: ChatCompletionRequest): LlmCompletion
}

/** Итог одного обращения: текст ответа, кто ответил, почему остановился и сколько токенов ушло. */
data class LlmCompletion(
    val content: String,
    val model: String? = null,
    val finishReason: String? = null,
    val usage: TokenUsage? = null,
)

data class TokenUsage(
    /** Весь вход: persona + профиль + память + окно диалога + новый вопрос. */
    val promptTokens: Int,
    val completionTokens: Int,
    /** Часть входа, зачтенная из кэша префикса провайдера. */
    val cachedPromptTokens: Int = 0,
)

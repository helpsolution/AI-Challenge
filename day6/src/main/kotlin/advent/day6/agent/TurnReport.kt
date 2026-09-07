package advent.day6.agent

import advent.day6.llm.Usage
import java.time.Instant

/** Один шаг хода: что агент сделал и на какой миллисекунде от начала. */
data class TurnStep(
    val offsetMs: Long,
    val title: String,
    val detail: String? = null,
)

/**
 * Расход токенов по одному ходу. Токены рассуждения вынесены отдельно: они входят
 * в выходные и оплачиваются как выходные, но в тексте ответа их нет.
 */
data class TurnUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val reasoningTokens: Int,
    val cachedTokens: Int,
    val totalTokens: Int,
)

fun Usage.toTurnUsage() = TurnUsage(
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    reasoningTokens = completionTokensDetails?.reasoningTokens ?: 0,
    cachedTokens = promptCacheHitTokens ?: 0,
    totalTokens = totalTokens,
)

/**
 * Протокол одного хода: что агент получил, что собрал, что ответила модель и сколько
 * это заняло. Именно он делает работу агента наблюдаемой — интерфейс строит по нему
 * таймлайн, а не пересказывает происходившее.
 */
data class TurnReport(
    val number: Int,
    val startedAt: Instant,
    val input: String,
    val answer: String?,
    val reasoning: String?,
    val model: String,
    /** Сколько сообщений ушло в промпт, включая системное. */
    val promptMessages: Int,
    val latencyMs: Long,
    val usage: TurnUsage?,
    val finishReason: String?,
    val error: String?,
    val steps: List<TurnStep>,
) {
    val failed: Boolean get() = error != null
}

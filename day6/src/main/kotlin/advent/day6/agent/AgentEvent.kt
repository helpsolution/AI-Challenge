package advent.day6.agent

import advent.day6.llm.Usage
import java.time.Instant

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

/** Один шаг хода: что агент сделал и на какой миллисекунде от начала. */
data class TurnStep(
    val offsetMs: Long,
    val title: String,
    val detail: String? = null,
)

/** Полный протокол одного хода — от получения запроса до разбора ответа. */
data class TurnReport(
    val number: Int,
    val startedAt: Instant,
    val input: String,
    val answer: String?,
    val reasoning: String?,
    val model: String,
    /** Сколько сообщений ушло в промпт, включая системное. */
    val promptMessages: Int,
    val firstTokenMs: Long?,
    val latencyMs: Long,
    val usage: TurnUsage?,
    val finishReason: String?,
    val error: String?,
    val steps: List<TurnStep>,
) {
    val failed: Boolean get() = error != null
}

/**
 * События, которые агент выдаёт по ходу работы. Агент не знает, кто их слушает:
 * SSE-контроллер, тест или лог — это уже забота слушателя.
 */
sealed interface AgentEvent {
    data class StateChanged(val state: AgentState, val mood: Mood) : AgentEvent
    data class Step(val step: TurnStep) : AgentEvent
    data class Token(val text: String) : AgentEvent
    data class Reasoning(val text: String) : AgentEvent
    data class Completed(val turn: TurnReport) : AgentEvent
    data class Failed(val turn: TurnReport) : AgentEvent
}

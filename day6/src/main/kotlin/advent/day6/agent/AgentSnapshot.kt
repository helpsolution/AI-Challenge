package advent.day6.agent

import java.time.Instant

enum class LogTone { NEUTRAL, INFO, SUCCESS, DANGER }

/** Запись журнала жизни агента: создан, перенастроен, ответил, не смог. */
data class LogEntry(
    val at: Instant,
    val tone: LogTone,
    val message: String,
)

data class AgentStats(
    val turns: Int,
    val failures: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalLatencyMs: Long,
) {
    val averageLatencyMs: Long get() = if (turns == 0) 0 else totalLatencyMs / turns
}

/** Всё, что интерфейсу нужно знать об агенте в один момент времени. */
data class AgentSnapshot(
    val name: String,
    val avatar: Avatar,
    val state: AgentState,
    val mood: Mood,
    val settings: AgentSettings,
    val availableModels: List<String>,
    val stats: AgentStats,
    val lastTurn: TurnReport?,
    val log: List<LogEntry>,
    val createdAt: Instant,
    val lastActivityAt: Instant?,
)

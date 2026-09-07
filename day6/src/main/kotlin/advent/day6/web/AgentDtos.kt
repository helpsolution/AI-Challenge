package advent.day6.web

import advent.day6.agent.AgentSettings
import advent.day6.agent.AgentSnapshot
import advent.day6.agent.AgentState
import advent.day6.agent.Avatar
import advent.day6.agent.Mood
import advent.day6.agent.TurnReport

data class ChatRequest(val text: String)

/** Настройки с фронта. Проверяет их сам агент — здесь только форма. */
data class SettingsRequest(
    val name: String,
    val avatar: Avatar,
    val persona: String = "",
    val model: String,
    val temperature: Double,
    val maxTokens: Int,
    val memoryWindow: Int,
) {
    fun toSettings() = AgentSettings(
        name = name.trim(),
        avatar = avatar,
        persona = persona.trim(),
        model = model.trim(),
        temperature = temperature,
        maxTokens = maxTokens,
        memoryWindow = memoryWindow,
    )
}

/** Тело SSE-события `state`. */
data class StateView(val state: AgentState, val mood: Mood)

/** Тело SSE-событий `token` и `reasoning`. */
data class TextChunk(val text: String)

/** Тело завершающих SSE-событий `done` и `error`: протокол хода и свежий снимок агента. */
data class TurnOutcome(val turn: TurnReport, val agent: AgentSnapshot)

data class ErrorResponse(
    val error: String,
    val details: List<String> = emptyList(),
)

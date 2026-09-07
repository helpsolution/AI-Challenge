package advent.day6.web

import advent.day6.agent.AgentSettings
import advent.day6.agent.AgentSnapshot
import advent.day6.agent.Avatar
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
) {
    fun toSettings() = AgentSettings(
        name = name.trim(),
        avatar = avatar,
        persona = persona.trim(),
        model = model.trim(),
        temperature = temperature,
        maxTokens = maxTokens,
    )
}

/** Ответ на вопрос: протокол хода и свежий снимок агента после него. */
data class TurnOutcome(
    val turn: TurnReport,
    val agent: AgentSnapshot,
)

data class ErrorResponse(
    val error: String,
    val details: List<String> = emptyList(),
)

package advent.day6.config

import advent.day6.agent.AgentSettings
import advent.day6.agent.Avatar
import advent.day6.agent.PersonaPresets
import org.springframework.boot.context.properties.ConfigurationProperties

/** Начальные настройки агента — с чем он просыпается при старте. Дальше их меняют из интерфейса. */
@ConfigurationProperties(prefix = "agent")
data class AgentProperties(
    val name: String = PersonaPresets.CAT.name,
    val avatar: Avatar = PersonaPresets.CAT.avatar,
    val persona: String = PersonaPresets.CAT.persona,
    val model: String = "deepseek-chat",
    val temperature: Double = 0.8,
    val maxTokens: Int = 1024,
) {
    fun toSettings() = AgentSettings(
        name = name.trim(),
        avatar = avatar,
        persona = persona.trim(),
        model = model,
        temperature = temperature,
        maxTokens = maxTokens,
    )
}

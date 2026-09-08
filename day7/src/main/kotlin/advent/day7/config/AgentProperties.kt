package advent.day7.config

import advent.day7.agent.AgentSettings
import org.springframework.boot.context.properties.ConfigurationProperties

/** Настройки агента из `application.yml`. */
@ConfigurationProperties(prefix = "agent")
data class AgentProperties(
    val name: String = "Барсик",
    val persona: String = "",
    val model: String = "deepseek-chat",
    val temperature: Double = 0.8,
    val maxTokens: Int = 1024,
) {
    fun toSettings() = AgentSettings(
        name = name.trim(),
        persona = persona.trim(),
        model = model.trim(),
        temperature = temperature,
        maxTokens = maxTokens,
    )
}

package advent.day8.config

import advent.day8.agent.AgentSettings
import org.springframework.boot.context.properties.ConfigurationProperties

/** Настройки агента из `application.yml`. */
@ConfigurationProperties(prefix = "agent")
data class AgentProperties(
    val name: String = "Барсик",
    val persona: String = "",
    val model: String = "openai/gpt-3.5-turbo-0613",
    val temperature: Double = 0.8,
    val maxTokens: Int = 512,
    /** Лимит контекста модели. Меняется вместе с `model` и `llm.provider`. */
    val contextLimit: Int = 4095,
) {
    fun toSettings() = AgentSettings(
        name = name.trim(),
        persona = persona.trim(),
        model = model.trim(),
        temperature = temperature,
        maxTokens = maxTokens,
        contextLimit = contextLimit,
    )
}

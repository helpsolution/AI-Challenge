package advent.posts.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "agent")
data class AgentProperties(
    val name: String = "Редактор",
    val model: String = "deepseek-flash",
    val maxTokens: Int = 1024,
    val temperature: Double = 0.3,
    val windowSize: Int = 6,
    val longTermLimit: Int = 30,
    val persona: String = "",
)

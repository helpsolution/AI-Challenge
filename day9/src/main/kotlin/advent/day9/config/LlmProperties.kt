package advent.day9.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Какой из двух провайдеров обслуживает агента в этом запуске. */
@ConfigurationProperties(prefix = "llm")
data class LlmProperties(
    val provider: LlmProvider = LlmProvider.OPENROUTER,
)

enum class LlmProvider { DEEPSEEK, OPENROUTER }

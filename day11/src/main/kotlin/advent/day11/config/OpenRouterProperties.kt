package advent.day11.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Доступ к OpenRouter. Ключ остается только на бэкенде. */
@ConfigurationProperties(prefix = "llm.openrouter")
data class OpenRouterProperties(
    val baseUrl: String = "https://openrouter.ai/api/v1",
    val apiKey: String = "",
    val referer: String = "https://github.com/helpsolution/AI-Challenge",
    val title: String = "AI Advent Challenge day 11",
    val connectTimeout: Duration = Duration.ofSeconds(10),
    val readTimeout: Duration = Duration.ofSeconds(120),
)

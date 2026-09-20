package advent.cookingstate.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "llm.openrouter")
data class OpenRouterProperties(
    val baseUrl: String = "https://openrouter.ai/api/v1",
    val apiKey: String = "",
    val model: String = "google/gemini-2.5-flash-lite",
    val maxTokens: Int = 1200,
    val connectTimeout: Duration = Duration.ofSeconds(10),
    val readTimeout: Duration = Duration.ofSeconds(90),
)

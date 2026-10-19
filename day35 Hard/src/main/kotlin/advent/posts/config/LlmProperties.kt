package advent.posts.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Доступ к DeepSeek. Ключ остается только на бэкенде. */
@ConfigurationProperties(prefix = "llm")
data class LlmProperties(
    val baseUrl: String = "https://api.deepseek.com",
    val apiKey: String = "",
    val connectTimeout: Duration = Duration.ofSeconds(10),
    val readTimeout: Duration = Duration.ofSeconds(120),
)

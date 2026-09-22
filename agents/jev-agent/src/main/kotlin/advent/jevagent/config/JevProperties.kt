package advent.jevagent.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "jev")
data class JevProperties(
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val connectTimeout: Duration,
    val requestTimeout: Duration,
)

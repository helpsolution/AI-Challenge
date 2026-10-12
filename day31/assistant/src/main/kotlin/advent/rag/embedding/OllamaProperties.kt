package advent.rag.embedding

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("ollama")
data class OllamaProperties(
    val baseUrl: String,
    val model: String,
    val timeout: Duration,
    val documentPrefix: String,
    val queryPrefix: String,
)

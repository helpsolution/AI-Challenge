package advent.rag.llm

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Настройки из блока llm в application.yml. Ключ — из .env или окружения, в коде и в ответах API его нет. */
@ConfigurationProperties("llm")
data class LlmProperties(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: Double,
    val timeout: Duration,
)

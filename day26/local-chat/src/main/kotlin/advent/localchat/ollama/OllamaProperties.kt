package advent.localchat.ollama

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("ollama")
data class OllamaProperties(
    val baseUrl: String,
    val model: String,
    val temperature: Double,
    /** Пустая строка — запрос уходит без системного сообщения. */
    val systemPrompt: String,
    val timeout: Duration,
)

package advent.rag.llm

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("local-llm")
data class LocalLlmProperties(
    val baseUrl: String,
    val models: List<String>,
    val temperature: Double,
    val timeout: Duration,
    // Окно контекста. 32k по умолчанию резервирует столько памяти, что вторая модель вытесняет первую.
    val numCtx: Int,
) {
    init { require(models.isNotEmpty()) { "Нужна хотя бы одна локальная модель: OLLAMA_CHAT_MODELS" } }
}

@ConfigurationProperties("cloud-llm")
data class CloudLlmProperties(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: Double,
    val timeout: Duration,
)

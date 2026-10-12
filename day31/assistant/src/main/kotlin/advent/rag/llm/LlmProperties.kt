package advent.rag.llm
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("cloud-llm")
data class CloudLlmProperties(val baseUrl: String, val apiKey: String, val model: String,
    val temperature: Double, val timeout: Duration)

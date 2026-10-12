package advent.rag.reranking

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("reranker")
data class RerankerProperties(
    val baseUrl: String,
    val timeout: Duration,
)

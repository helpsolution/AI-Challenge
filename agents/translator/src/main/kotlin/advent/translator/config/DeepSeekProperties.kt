package advent.translator.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "llm.deepseek")
data class DeepSeekProperties(
    val baseUrl: String = "https://api.deepseek.com",
    val apiKey: String = "",
    val model: String = "deepseek-chat",
    val temperature: Double = 0.0,
    val maxTokens: Int = 2048,
    val connectTimeout: Duration = Duration.ofSeconds(10),
    val readTimeout: Duration = Duration.ofSeconds(120),
) {
    init {
        require(baseUrl.isNotBlank()) { "llm.deepseek.base-url не должен быть пустым" }
        require(model.isNotBlank()) { "llm.deepseek.model не должен быть пустым" }
        require(temperature in 0.0..2.0) { "llm.deepseek.temperature должен быть от 0 до 2" }
        require(maxTokens > 0) { "llm.deepseek.max-tokens должен быть положительным" }
        require(!connectTimeout.isNegative && !connectTimeout.isZero) { "llm.deepseek.connect-timeout должен быть положительным" }
        require(!readTimeout.isNegative && !readTimeout.isZero) { "llm.deepseek.read-timeout должен быть положительным" }
    }
}

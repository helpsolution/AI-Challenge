package advent.day2.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Настройки доступа к LLM-провайдеру. Ключ читается из переменной окружения
 * и никогда не покидает бэкенд — фронт про него не знает.
 */
@ConfigurationProperties(prefix = "llm.deepseek")
data class DeepSeekProperties(
    val baseUrl: String = "https://api.deepseek.com",
    val apiKey: String = "",
    val defaultModel: String = "deepseek-chat",
    /** Модели, которые разрешено выбирать с фронта. */
    val allowedModels: Set<String> = setOf("deepseek-chat", "deepseek-reasoner"),
    val connectTimeout: Duration = Duration.ofSeconds(10),
    val readTimeout: Duration = Duration.ofSeconds(120),
)

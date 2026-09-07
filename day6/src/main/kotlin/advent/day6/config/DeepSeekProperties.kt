package advent.day6.config

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
    /** Модели, которые агенту разрешено использовать. Выбор в настройках ограничен этим списком. */
    val allowedModels: Set<String> = setOf("deepseek-chat", "deepseek-reasoner"),
    val connectTimeout: Duration = Duration.ofSeconds(10),
    val readTimeout: Duration = Duration.ofSeconds(180),
)

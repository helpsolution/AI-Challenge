package advent.pipeline.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.time.Duration

/** Настройки из блока habr в application.yml: адреса лент и ограничения на скачивание. */
@ConfigurationProperties("habr")
data class HabrProperties(
    val articlesUrl: String,
    val searchUrl: String,
    val fetchTimeout: Duration,
    val maxFeedSize: DataSize,
    val userAgent: String,
)

/** Настройки из блока llm: к какой модели ходит шаг summarize. */
@ConfigurationProperties("llm")
data class LlmProperties(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val timeout: Duration,
) {
    init {
        // Без ключа summarize не работает, а сервис без summarize — это полконвейера. Лучше не стартовать вовсе.
        require(apiKey.isNotBlank()) { "Не задан DEEPSEEK_API_KEY: положите его в day19/.env или в переменную окружения" }
    }
}

package advent.day9.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Доступ к OpenRouter — агрегатору, через который в этот день берутся модели с маленьким
 * контекстом. Ключ, как и у DeepSeek, живёт только на бэкенде.
 */
@ConfigurationProperties(prefix = "llm.openrouter")
data class OpenRouterProperties(
    val baseUrl: String = "https://openrouter.ai/api/v1",
    val apiKey: String = "",
    /** Заголовки атрибуции: OpenRouter показывает по ним источник трафика. Не обязательны. */
    val referer: String = "https://github.com/helpsolution/AI-Challenge",
    val title: String = "AI Advent Challenge — day 8",
    /**
     * Сжимать ли промпт, если он не влезает в контекст модели.
     *
     * Здесь и находится главный переключатель дня. `true` — агрегатор молча вырежет
     * середину истории и ответит как ни в чём не бывало: агент потеряет память, а признаком
     * этого будет только расхождение в счётчике токенов. `false` — тот же запрос честно
     * упадёт с `400` и текстом про лимит.
     *
     * По умолчанию `false`: поломку лучше видеть, чем не заметить.
     */
    val contextCompression: Boolean = false,
    val connectTimeout: Duration = Duration.ofSeconds(10),
    val readTimeout: Duration = Duration.ofSeconds(180),
)

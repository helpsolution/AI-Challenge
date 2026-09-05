package advent.day5.config

import advent.day5.catalog.ModelTier
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Настройки доступа к провайдеру. Ключ читается из переменной окружения
 * и никогда не покидает бэкенд — фронт про него не знает.
 *
 * День 5 ходит не в DeepSeek напрямую, а через OpenRouter: задание требует три модели
 * разного класса, а у DeepSeek их две. OpenRouter — тот же OpenAI-совместимый протокол,
 * поэтому DTO и клиент из дня 4 переехали почти без правок, зато в ответе приходит
 * реальная списанная стоимость запроса, а не её оценка по прайс-листу.
 */
@ConfigurationProperties(prefix = "llm.openrouter")
data class OpenRouterProperties(
    val baseUrl: String = "https://openrouter.ai/api/v1",
    val apiKey: String = "",
    /** Заголовки атрибуции: OpenRouter показывает по ним источник трафика. Не обязательны. */
    val referer: String = "https://github.com/helpsolution/AI-Challenge",
    val title: String = "AI Advent Challenge — day 5",
    /** Каталог моделей, разложенный по трём уровням. Выбор с фронта ограничен им. */
    val catalog: List<CatalogEntry> = emptyList(),
    val connectTimeout: Duration = Duration.ofSeconds(10),
    val readTimeout: Duration = Duration.ofSeconds(180),
    /** Откуда берём прайс и ссылки на карточки моделей. Публичная ручка, ключ не нужен. */
    val modelsUrl: String = "https://openrouter.ai/api/v1/models",
)

/**
 * Описание одной модели в каталоге. Здесь только то, что не меняется:
 * к какому уровню отнесена и как её назвать по-человечески.
 * Цена, размер контекста и ссылка на HuggingFace подтягиваются у провайдера —
 * прайс меняется, и захардкоженный однажды он начинает врать.
 */
data class CatalogEntry(
    val id: String = "",
    val tier: ModelTier = ModelTier.MEDIUM,
    /** Короткое имя для интерфейса: «Llama 3.2 1B» вместо meta-llama/llama-3.2-1b-instruct. */
    val title: String = "",
    /** Размер модели словами: «1B параметров», «открытая MoE», «закрытая, размер не раскрыт». */
    val scale: String = "",
    /** Уровень выбран по умолчанию именно на этой модели. */
    val default: Boolean = false,
)

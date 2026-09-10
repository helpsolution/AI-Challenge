package advent.day9.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Доступ к DeepSeek. Ключ читается из переменной окружения и никогда не покидает
 * бэкенд — фронт про него не знает.
 */
@ConfigurationProperties(prefix = "llm.deepseek")
data class DeepSeekProperties(
    val baseUrl: String = "https://api.deepseek.com",
    val apiKey: String = "",
    val connectTimeout: Duration = Duration.ofSeconds(10),
    val readTimeout: Duration = Duration.ofSeconds(180),
    val prices: Prices = Prices(),
)

/**
 * Прайс в долларах за миллион токенов. Нужен потому, что DeepSeek, в отличие от
 * OpenRouter, стоимость запроса не сообщает — а день просит показать деньги.
 *
 * Вход считается по двум ставкам, и разница между ними велика: то, что зачлось из кэша
 * префикса, дешевле нового текста примерно в тридцать раз. Для растущей истории это
 * определяющее обстоятельство: переписка отправляется заново на каждом ходу, но платим
 * за неё по кэш-ставке.
 *
 * Нули означают «прайс не задан»: тогда цену не показываем вовсе. Выдуманное число
 * хуже прочерка — по нему начнут принимать решения.
 */
data class Prices(
    val inputCacheHit: Double = 0.0,
    val inputCacheMiss: Double = 0.0,
    val output: Double = 0.0,
) {
    val isSet: Boolean get() = inputCacheMiss > 0 || output > 0
}

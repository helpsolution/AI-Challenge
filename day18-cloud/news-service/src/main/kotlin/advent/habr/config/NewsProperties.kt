package advent.habr.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.time.Duration

/** Настройки из блока news в application.yml: адрес ленты, расписание и ограничения на сбор. */
@ConfigurationProperties("news")
data class NewsProperties(
    val feedUrl: String,
    val every: Duration,
    val retention: Duration,
    val fetchTimeout: Duration,
    val maxFeedSize: DataSize,
    val userAgent: String,
)

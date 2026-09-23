package advent.news.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.time.Duration

/** Настройки из блока news в application.yml: каталог лент, адрес поиска тем и ограничения на сбор. */
@ConfigurationProperties("news")
data class NewsProperties(
    val retention: Duration,
    val fetchTimeout: Duration,
    val maxFeedSize: DataSize,
    val userAgent: String,
    val googleNews: GoogleNews,
    /** Порядок как в application.yml: Spring собирает карту в LinkedHashMap. */
    val sources: Map<String, Source>,
) {
    data class GoogleNews(
        val url: String,
        val language: String,
        val country: String,
    )

    data class Source(
        val title: String,
        val url: String,
    )
}

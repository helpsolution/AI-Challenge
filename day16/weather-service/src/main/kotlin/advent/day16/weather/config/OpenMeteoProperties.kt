package advent.day16.weather.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "open-meteo")
data class OpenMeteoProperties(
    val geocodingUrl: String,
    val forecastUrl: String,
    val language: String,
    val connectTimeout: Duration,
    val readTimeout: Duration,
)

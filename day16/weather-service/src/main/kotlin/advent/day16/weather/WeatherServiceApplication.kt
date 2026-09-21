package advent.day16.weather

import advent.day16.weather.config.OpenMeteoProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

/**
 * Обычный бизнес-сервис: знает про погоду и ничего не знает про MCP и модели.
 * MCP-сервер из соседнего модуля обращается к нему по тому же HTTP API, что и любой другой клиент.
 */
@SpringBootApplication
@EnableConfigurationProperties(OpenMeteoProperties::class)
class WeatherServiceApplication

fun main(args: Array<String>) {
    runApplication<WeatherServiceApplication>(*args)
}

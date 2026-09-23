package advent.news

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Первый шаг дня 18: сервис, который сам, по расписанию, собирает новости из RSS в SQLite
 * и отдаёт по ним агрегированную сводку. Про MCP и про модели он не знает ничего —
 * инструменты живут отдельным модулем и ходят в это же HTTP API, что и любой другой клиент.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
class NewsServiceApplication

fun main(args: Array<String>) {
    runApplication<NewsServiceApplication>(*args)
}

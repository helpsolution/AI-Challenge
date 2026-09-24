package advent.habr

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Облачная версия дня 18, первый из трёх процессов: сервис, который сам, по расписанию,
 * собирает статьи Хабра в SQLite и отдаёт по ним агрегированную сводку.
 * Про MCP, модели и Telegram он не знает ничего — они ходят в его HTTP API.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
class NewsServiceApplication

fun main(args: Array<String>) {
    runApplication<NewsServiceApplication>(*args)
}

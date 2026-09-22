package advent.users

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Первый шаг дня 17: обычный сервис пользователей с REST API поверх SQLite.
 * Про MCP и про модели он не знает ничего — инструменты появятся отдельным модулем,
 * который будет ходить в это же HTTP API, что и любой другой клиент.
 */
@SpringBootApplication
class UserServiceApplication

fun main(args: Array<String>) {
    runApplication<UserServiceApplication>(*args)
}

package advent.users.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfiguration {
    @Bean
    fun usersOpenApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("Сервис пользователей — день 17")
            .version("1.0.0")
            .description(
                "Две операции: создать пользователя и найти пользователя. " +
                    "Хранилище — SQLite. Это тестовый сервис, вокруг которого дальше вырастет MCP-инструмент.",
            ),
    )
}

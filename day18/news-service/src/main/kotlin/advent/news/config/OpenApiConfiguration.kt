package advent.news.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfiguration {
    @Bean
    fun newsOpenApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("Сервис новостей — день 18")
            .version("1.0.0")
            .description(
                "Подписки на RSS-ленты и темы Google News. Сервис сам забирает их по расписанию, " +
                    "складывает новости в SQLite и отдаёт агрегированную сводку за период.",
            ),
    )
}

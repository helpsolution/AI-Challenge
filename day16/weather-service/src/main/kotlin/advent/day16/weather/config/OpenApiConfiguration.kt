package advent.day16.weather.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfiguration {
    @Bean
    fun weatherOpenApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("Сервис погоды — день 16")
            .version("1.0.0")
            .description(
                "Обычный REST-сервис поверх Open-Meteo. Про MCP не знает ничего: " +
                    "MCP-сервер обращается к этим же эндпоинтам, что и любой другой клиент.",
            ),
    )
}

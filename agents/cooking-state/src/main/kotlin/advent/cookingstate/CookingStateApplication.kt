package advent.cookingstate

import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.info.Info
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
@OpenAPIDefinition(info = Info(
    title = "Агент «Что приготовить?»",
    version = "1.0",
    description = "Пять состояний диалога: условия, выбор, рецепт, готовка и завершение",
))
class CookingStateApplication

fun main(args: Array<String>) {
    runApplication<CookingStateApplication>(*args)
}

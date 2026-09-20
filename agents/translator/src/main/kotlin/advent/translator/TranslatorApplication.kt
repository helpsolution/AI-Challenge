package advent.translator

import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.info.Info
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
@OpenAPIDefinition(
    info = Info(
        title = "Агент-переводчик",
        version = "1.0",
        description = "Переводит текст на настроенные языки через DeepSeek",
    ),
)
class TranslatorApplication

fun main(args: Array<String>) {
    runApplication<TranslatorApplication>(*args)
}

package advent.foodphoto

import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.info.Info
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
@OpenAPIDefinition(
    info = Info(
        title = "Агент распознавания еды по фото",
        version = "1.0",
        description = "Описывает видимую еду по фотографии через OpenRouter",
    ),
)
class FoodPhotoApplication

fun main(args: Array<String>) {
    runApplication<FoodPhotoApplication>(*args)
}

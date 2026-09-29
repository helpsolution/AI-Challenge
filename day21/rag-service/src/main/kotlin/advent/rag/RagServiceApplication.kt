package advent.rag

import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.info.Info
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
@OpenAPIDefinition(
    info = Info(
        title = "Day21 · RAG",
        version = "0.1",
        description = "Кубики RAG-пайплайна по одному. Сейчас готов первый: текст → вектор через локальную Ollama.",
    ),
)
class RagServiceApplication

fun main(args: Array<String>) {
    runApplication<RagServiceApplication>(*args)
}

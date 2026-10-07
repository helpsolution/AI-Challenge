package advent.studio

import advent.localllm.OllamaClient
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

@ConfigurationProperties("ollama")
data class OllamaProperties(val baseUrl: String, val model: String)

@SpringBootApplication
@ConfigurationPropertiesScan
class StudioApplication {
    @Bean
    fun ollamaClient(properties: OllamaProperties) = OllamaClient(properties.baseUrl.trimEnd('/'), properties.model)
}

fun main(args: Array<String>) {
    runApplication<StudioApplication>(*args)
}

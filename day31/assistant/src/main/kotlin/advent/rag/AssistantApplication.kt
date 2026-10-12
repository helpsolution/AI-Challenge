package advent.rag

import advent.rag.llm.*
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper

@SpringBootApplication
@ConfigurationPropertiesScan
class AssistantApplication {
    @Bean fun chatModel(builder: RestClient.Builder, json: JsonMapper, properties: CloudLlmProperties): ChatModel =
        DeepSeekClient(builder, json, properties)
}
fun main(args: Array<String>) { runApplication<AssistantApplication>(*args) }

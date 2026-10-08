package advent.rag

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class RagAgentApplication

fun main(args: Array<String>) {
    runApplication<RagAgentApplication>(*args)
}

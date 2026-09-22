package advent.jevagent

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class JevAgentApplication

fun main(args: Array<String>) {
    runApplication<JevAgentApplication>(*args)
}

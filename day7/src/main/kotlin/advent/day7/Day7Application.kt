package advent.day7

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class Day7Application

fun main(args: Array<String>) {
    runApplication<Day7Application>(*args)
}

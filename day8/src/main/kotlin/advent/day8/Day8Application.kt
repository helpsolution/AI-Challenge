package advent.day8

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class Day8Application

fun main(args: Array<String>) {
    runApplication<Day8Application>(*args)
}

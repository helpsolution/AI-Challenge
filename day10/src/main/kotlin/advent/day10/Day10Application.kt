package advent.day10

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class Day10Application

fun main(args: Array<String>) {
    runApplication<Day10Application>(*args)
}

package advent.day9

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class Day9Application

fun main(args: Array<String>) {
    runApplication<Day9Application>(*args)
}

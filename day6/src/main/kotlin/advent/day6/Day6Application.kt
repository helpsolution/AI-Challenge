package advent.day6

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class Day6Application

fun main(args: Array<String>) {
    runApplication<Day6Application>(*args)
}

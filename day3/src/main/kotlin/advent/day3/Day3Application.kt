package advent.day3

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class Day3Application

fun main(args: Array<String>) {
    runApplication<Day3Application>(*args)
}

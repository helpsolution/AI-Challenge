package advent.day5

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class Day5Application

fun main(args: Array<String>) {
    runApplication<Day5Application>(*args)
}

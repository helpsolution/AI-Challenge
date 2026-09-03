package advent.day4

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class Day4Application

fun main(args: Array<String>) {
    runApplication<Day4Application>(*args)
}

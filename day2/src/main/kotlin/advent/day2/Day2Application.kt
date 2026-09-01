package advent.day2

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class Day2Application

fun main(args: Array<String>) {
    runApplication<Day2Application>(*args)
}

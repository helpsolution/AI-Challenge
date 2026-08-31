package advent.day1

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class Day1Application

fun main(args: Array<String>) {
    runApplication<Day1Application>(*args)
}

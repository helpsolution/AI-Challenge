package advent.pipeline

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication


@SpringBootApplication
@ConfigurationPropertiesScan
class HabrServiceApplication

fun main(args: Array<String>) {
    runApplication<HabrServiceApplication>(*args)
}

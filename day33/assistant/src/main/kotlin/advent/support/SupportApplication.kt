package advent.support

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class SupportApplication

fun main(args: Array<String>) { runApplication<SupportApplication>(*args) }

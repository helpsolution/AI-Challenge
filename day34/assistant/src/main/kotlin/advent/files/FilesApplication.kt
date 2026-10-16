package advent.files

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class FilesApplication

fun main(args: Array<String>) { runApplication<FilesApplication>(*args) }

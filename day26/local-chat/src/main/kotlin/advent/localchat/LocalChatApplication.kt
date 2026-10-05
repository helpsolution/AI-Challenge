package advent.localchat

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class LocalChatApplication

fun main(args: Array<String>) {
    runApplication<LocalChatApplication>(*args)
}

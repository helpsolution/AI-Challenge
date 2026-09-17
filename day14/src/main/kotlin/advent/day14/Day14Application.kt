package advent.day14

import advent.day14.config.AgentProperties
import advent.day14.config.ChatProperties
import advent.day14.config.OpenRouterProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.time.Clock

@SpringBootApplication
@EnableConfigurationProperties(
    AgentProperties::class,
    ChatProperties::class,
    OpenRouterProperties::class,
)
class Day14Application {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

fun main(args: Array<String>) {
    runApplication<Day14Application>(*args)
}

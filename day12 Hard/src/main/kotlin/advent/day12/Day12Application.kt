package advent.day12

import advent.day12.config.AgentProperties
import advent.day12.config.ChatProperties
import advent.day12.config.OpenRouterProperties
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
class Day12Application {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

fun main(args: Array<String>) {
    runApplication<Day12Application>(*args)
}

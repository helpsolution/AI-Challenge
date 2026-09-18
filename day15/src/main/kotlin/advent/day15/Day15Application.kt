package advent.day15

import advent.day15.config.AgentProperties
import advent.day15.config.ChatProperties
import advent.day15.config.OpenRouterProperties
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
class Day15Application {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

fun main(args: Array<String>) {
    runApplication<Day15Application>(*args)
}

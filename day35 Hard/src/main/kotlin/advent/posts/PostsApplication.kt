package advent.posts

import advent.posts.config.AgentProperties
import advent.posts.config.ChatProperties
import advent.posts.config.LlmProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.time.Clock

@SpringBootApplication
@EnableConfigurationProperties(
    AgentProperties::class,
    ChatProperties::class,
    LlmProperties::class,
)
class PostsApplication {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

fun main(args: Array<String>) {
    runApplication<PostsApplication>(*args)
}

package advent.llmservice

import advent.llmservice.limits.ApiKeyInterceptor
import advent.llmservice.limits.GenerationQueue
import advent.llmservice.limits.RateLimiter
import advent.llmservice.ollama.OllamaClient
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

@ConfigurationProperties("llm")
data class LlmProperties(
    val ollamaUrl: String,
    val model: String,
    val contextTokens: Int,
    val maxOutputTokens: Int,
    val apiKeys: String,
    val rateLimit: RateLimit,
    val queue: Queue,
) {
    data class RateLimit(val requests: Int, val window: Duration)
    data class Queue(val parallel: Int, val maxWaiting: Int)

    /** «имя:ключ,имя:ключ» → ключ → имя клиента. */
    fun clients(): Map<String, String> = apiKeys.split(',').map { it.trim() }.filter { it.isNotEmpty() }.associate { entry ->
        val name = entry.substringBefore(':').trim()
        val key = entry.substringAfter(':', "").trim()
        require(name.isNotEmpty() && key.isNotEmpty()) { "API_KEYS: ожидается «имя:ключ», получено «${entry.take(12)}…»" }
        key to name
    }
}

@SpringBootApplication
@ConfigurationPropertiesScan
class LlmServiceApplication(private val props: LlmProperties) : WebMvcConfigurer {

    @Bean
    fun ollamaClient(json: JsonMapper) = OllamaClient(props.ollamaUrl.trimEnd('/'), json)

    @Bean
    fun rateLimiter() = RateLimiter(props.rateLimit.requests, props.rateLimit.window)

    @Bean
    fun generationQueue() = GenerationQueue(props.queue.parallel, props.queue.maxWaiting)

    // Всё, кроме статики страницы, — только с ключом: и модель, и статус, и журнал.
    override fun addInterceptors(registry: InterceptorRegistry) {
        val clients = props.clients()
        check(clients.isNotEmpty()) { "Не задан ни один ключ доступа. Пример: API_KEYS=owner:$(openssl rand -hex 24)" }
        registry.addInterceptor(ApiKeyInterceptor(clients)).addPathPatterns("/v1/**", "/api/**")
    }
}

fun main(args: Array<String>) {
    runApplication<LlmServiceApplication>(*args)
}

package advent.rag.embedding

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

/** Настройки из блока ollama в application.yml: где живёт Ollama и какой моделью считать эмбеддинги. */
@ConfigurationProperties("ollama")
data class OllamaProperties(
    val baseUrl: String,
    val model: String,
    val timeout: Duration,
    /** Префикс задачи перед текстом документа — так модель обучали кодировать то, по чему ищут. */
    val documentPrefix: String,
)

@Configuration
class OllamaConfiguration {
    /** Клиент к Ollama. JSON в обе стороны переводит Jackson приложения, руками ничего не сериализуем. */
    @Bean
    fun ollamaRestClient(builder: RestClient.Builder, properties: OllamaProperties): RestClient {
        val http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()
        return builder
            .baseUrl(properties.baseUrl)
            .requestFactory(JdkClientHttpRequestFactory(http).apply { setReadTimeout(properties.timeout) })
            .build()
    }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}

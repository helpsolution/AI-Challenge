package advent.lab

import advent.lab.ollama.OllamaClient
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpClient
import java.time.Duration

@ConfigurationProperties("ollama")
data class OllamaProperties(val baseUrl: String, val timeout: Duration)

@SpringBootApplication
@ConfigurationPropertiesScan
class LabApplication {
    @Bean
    fun ollamaClient(builder: RestClient.Builder, json: JsonMapper, properties: OllamaProperties): OllamaClient {
        val baseUrl = properties.baseUrl.trimEnd('/')
        val http = builder
            .baseUrl(baseUrl)
            .requestFactory(JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build())
                .apply { setReadTimeout(properties.timeout) })
            .build()
        return OllamaClient(http, json, baseUrl)
    }
}

fun main(args: Array<String>) {
    runApplication<LabApplication>(*args)
}

package advent.day6.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient

@Configuration
class RestClientConfig {

    /**
     * Клиент на java.net.http: тело ответа отдаётся потоком, а не целиком после закрытия
     * соединения. Для стриминга это принципиально — токены должны доходить до агента
     * по мере генерации, иначе «живого» ответа не получится.
     */
    @Bean
    fun deepSeekRestClient(properties: DeepSeekProperties): RestClient {
        val httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply {
            setReadTimeout(properties.readTimeout)
        }

        return RestClient.builder()
            .baseUrl(properties.baseUrl)
            .requestFactory(requestFactory)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${properties.apiKey}")
            .build()
    }
}

package advent.day5.config

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient

@Configuration
class RestClientConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Клиент на java.net.http: в отличие от HttpURLConnection он умеет HTTP/2 и
     * мультиплексирует параллельные запросы в одно соединение. Для дня 5 это важно —
     * три модели опрашиваются одной пачкой, и отдельный TLS-хендшейк на каждый
     * запрос иногда рвётся.
     */
    @Bean
    fun openRouterRestClient(properties: OpenRouterProperties): RestClient {
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
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader("HTTP-Referer", ascii(properties.referer))
            .defaultHeader("X-Title", ascii(properties.title))
            .build()
    }

    /**
     * Значения HTTP-заголовков обязаны быть ASCII: java.net.http отвергает запрос целиком,
     * и один русский символ в названии приложения роняет все обращения к модели с невнятным
     * «invalid header value». Заголовки эти — необязательная атрибуция, поэтому лишние
     * символы из них вычищаются с предупреждением, а не обрушивают работу.
     */
    private fun ascii(value: String): String {
        val cleaned = value.filter { it.code in 0x20..0x7E }.trim()
        if (cleaned != value.trim()) {
            log.warn("Из заголовка убраны не-ASCII символы: '{}' → '{}'", value, cleaned)
        }
        return cleaned
    }

    /** Отдельный клиент без ключа — за публичным каталогом моделей. */
    @Bean
    fun catalogRestClient(properties: OpenRouterProperties): RestClient =
        RestClient.builder()
            .requestFactory(
                JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build(),
                ).apply { setReadTimeout(java.time.Duration.ofSeconds(15)) },
            )
            .build()
}

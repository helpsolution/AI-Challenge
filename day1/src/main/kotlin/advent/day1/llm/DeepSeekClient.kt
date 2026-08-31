package advent.day1.llm

import advent.day1.config.DeepSeekProperties
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.databind.ObjectMapper

/**
 * Тонкая обёртка над HTTP API провайдера. Единственное место в приложении,
 * которое знает про сеть и API-ключ.
 *
 * Тело запроса сериализуется здесь вручную, а ответ читается строкой: так интерфейс
 * получает ровно те байты, которыми обменялись с провайдером, а не их реконструкцию.
 */
@Service
class DeepSeekClient(
    private val deepSeekRestClient: RestClient,
    private val properties: DeepSeekProperties,
    private val objectMapper: ObjectMapper,
) : LlmClient {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun complete(request: ChatCompletionRequest): LlmExchange {
        if (properties.apiKey.isBlank()) {
            throw LlmException("Не задан API-ключ: переменная окружения DEEPSEEK_API_KEY пуста")
        }

        val requestBody = objectMapper.writeValueAsString(request)
        val url = properties.baseUrl.trimEnd('/') + COMPLETIONS_PATH
        val headers = mapOf(
            "Content-Type" to MediaType.APPLICATION_JSON_VALUE,
            "Authorization" to "Bearer ${maskKey(properties.apiKey)}",
        )

        fun exchange(status: Int?, responseBody: String?, parsed: ChatCompletionResponse?) = LlmExchange(
            url = url,
            method = "POST",
            requestHeaders = headers,
            requestBody = requestBody,
            status = status,
            responseBody = responseBody,
            parsed = parsed,
        )

        return try {
            deepSeekRestClient.post()
                .uri(COMPLETIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .exchange { _, clientResponse ->
                    val status = clientResponse.statusCode
                    val rawBody = clientResponse.bodyTo(String::class.java).orEmpty()

                    if (status.isError) {
                        log.warn("LLM ответил ошибкой {}: {}", status, rawBody.take(500))
                        throw LlmException(
                            message = providerErrorMessage(status.value(), rawBody),
                            providerStatus = status,
                            exchange = exchange(status.value(), rawBody, null),
                        )
                    }

                    exchange(
                        status = status.value(),
                        responseBody = rawBody,
                        parsed = objectMapper.readValue(rawBody, ChatCompletionResponse::class.java),
                    )
                }
        } catch (e: RestClientException) {
            throw LlmException(
                message = "Не удалось получить ответ от LLM: ${e.message}",
                exchange = exchange(null, null, null),
                cause = e,
            )
        }
    }

    private fun providerErrorMessage(status: Int, body: String): String = when (status) {
        401 -> "LLM отклонил ключ (401). Проверьте DEEPSEEK_API_KEY."
        402 -> "Недостаточно средств на балансе LLM-провайдера (402)."
        422 -> "LLM отклонил параметры запроса (422): ${body.take(300)}"
        429 -> "Превышен лимит запросов к LLM (429). Попробуйте позже."
        in 500..599 -> "LLM временно недоступен ($status). Попробуйте позже."
        else -> "LLM вернул ошибку $status: ${body.take(300)}"
    }

    /** Из `sk-1234…cdef` остаются только края — по ним ключ узнаётся, но не восстанавливается. */
    private fun maskKey(key: String): String =
        if (key.length <= 12) "***" else "${key.take(6)}…${key.takeLast(4)}"

    private companion object {
        const val COMPLETIONS_PATH = "/chat/completions"
    }
}

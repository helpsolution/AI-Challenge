package advent.day5.llm

import advent.day5.config.OpenRouterProperties
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.ResourceAccessException
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
class OpenRouterClient(
    private val openRouterRestClient: RestClient,
    private val properties: OpenRouterProperties,
    private val objectMapper: ObjectMapper,
) : LlmClient {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun complete(request: ChatCompletionRequest): LlmExchange {
        if (properties.apiKey.isBlank()) {
            throw LlmException("Не задан API-ключ: переменная окружения OPENROUTER_API_KEY пуста")
        }

        val requestBody = objectMapper.writeValueAsString(request)
        val context = ExchangeContext(
            url = properties.baseUrl.trimEnd('/') + COMPLETIONS_PATH,
            headers = mapOf(
                "Content-Type" to MediaType.APPLICATION_JSON_VALUE,
                "Authorization" to "Bearer ${maskKey(properties.apiKey)}",
                "HTTP-Referer" to properties.referer,
                "X-Title" to properties.title,
            ),
            requestBody = requestBody,
        )

        return try {
            withRetry { send(context) }
        } catch (e: RestClientException) {
            throw LlmException(
                message = "Не удалось получить ответ от ${request.model}: ${e.message}",
                exchange = context.toExchange(null, null, null),
                cause = e,
            )
        }
    }

    private fun send(context: ExchangeContext): LlmExchange =
        openRouterRestClient.post()
            .uri(COMPLETIONS_PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .body(context.requestBody)
            .exchange { _, clientResponse ->
                val status = clientResponse.statusCode
                val rawBody = clientResponse.bodyTo(String::class.java).orEmpty()

                if (status.isError) {
                    log.warn("Провайдер ответил ошибкой {}: {}", status, rawBody.take(500))
                    throw LlmException(
                        message = providerErrorMessage(status.value(), rawBody),
                        providerStatus = status,
                        exchange = context.toExchange(status.value(), rawBody, null),
                    )
                }

                val parsed = objectMapper.readValue(rawBody, ChatCompletionResponse::class.java)

                // OpenRouter умеет вернуть 200 и ошибку в теле — например, когда площадка
                // под моделью отказала. Без этой проверки пользователь увидел бы
                // невнятное «модель не вернула ни одного варианта».
                parsed.error?.let {
                    throw LlmException(
                        message = "Провайдер отказал: ${it.message ?: "причина не указана"}",
                        exchange = context.toExchange(status.value(), rawBody, parsed),
                    )
                }

                context.toExchange(status.value(), rawBody, parsed)
            }

    /**
     * Одна повторная попытка на сетевой сбой: разрыв TLS-хендшейка случается на пачке
     * параллельных запросов и лечится повтором. Ошибки самого API не ретраятся —
     * второй раз ответ будет тот же, только дороже.
     */
    private fun <T> withRetry(block: () -> T): T = try {
        block()
    } catch (e: ResourceAccessException) {
        log.warn("Сетевой сбой при обращении к провайдеру, повторяю: {}", e.message)
        block()
    }

    private fun providerErrorMessage(status: Int, body: String): String = when (status) {
        401 -> "OpenRouter отклонил ключ (401). Проверьте OPENROUTER_API_KEY."
        402 -> "Недостаточно средств на балансе OpenRouter (402). Пополните счёт."
        403 -> "Запрос отклонён модерацией провайдера (403): ${body.take(300)}"
        404 -> "Такой модели у провайдера нет (404): ${body.take(300)}"
        408 -> "Модель не ответила за отведённое время (408)."
        429 -> "Превышен лимит запросов (429). У бесплатных моделей он особенно низкий."
        502 -> "Площадка под моделью недоступна (502). Попробуйте другую модель."
        503 -> "Нет доступной площадки под эту модель (503)."
        in 500..599 -> "Провайдер временно недоступен ($status). Попробуйте позже."
        else -> "Провайдер вернул ошибку $status: ${body.take(300)}"
    }

    /** Из `sk-or-v1-1234…cdef` остаются только края — по ним ключ узнаётся, но не восстанавливается. */
    private fun maskKey(key: String): String =
        if (key.length <= 12) "***" else "${key.take(10)}…${key.takeLast(4)}"

    /** Неизменная часть обмена: она известна ещё до похода в сеть. */
    private data class ExchangeContext(
        val url: String,
        val headers: Map<String, String>,
        val requestBody: String,
    ) {
        fun toExchange(status: Int?, responseBody: String?, parsed: ChatCompletionResponse?) = LlmExchange(
            url = url,
            method = "POST",
            requestHeaders = headers,
            requestBody = requestBody,
            status = status,
            responseBody = responseBody,
            parsed = parsed,
        )
    }

    private companion object {
        const val COMPLETIONS_PATH = "/chat/completions"
    }
}

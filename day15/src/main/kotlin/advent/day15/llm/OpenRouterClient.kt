package advent.day15.llm

import advent.day15.config.OpenRouterProperties
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import java.net.URI

/** OpenAI-совместимый клиент OpenRouter для редактора постов. */
class OpenRouterClient(
    private val properties: OpenRouterProperties,
    private val objectMapper: ObjectMapper,
    private val http: HttpJson = HttpJson(properties.connectTimeout),
) : LlmClient {
    private val log = LoggerFactory.getLogger(javaClass)
    private val endpoint: URI = URI.create(properties.baseUrl.trimEnd('/') + COMPLETIONS_PATH)

    override fun complete(request: ChatCompletionRequest): LlmCompletion {
        if (properties.apiKey.isBlank()) {
            throw LlmException("Не задан API-ключ: переменная окружения OPENROUTER_API_KEY пуста")
        }

        val response = http.post(
            endpoint = endpoint,
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "Authorization" to "Bearer ${properties.apiKey}",
                "HTTP-Referer" to properties.referer,
                "X-Title" to properties.title,
            ),
            body = objectMapper.writeValueAsString(request),
            readTimeout = properties.readTimeout,
            log = log,
        )

        if (response.status >= 400) {
            log.warn("OpenRouter ответил ошибкой {}: {}", response.status, response.body.take(500))
            throw LlmException(
                message = errorMessage(response.status, response.body),
                providerStatus = response.status,
                providerBody = response.body,
            )
        }

        val parsed = objectMapper.readValue(response.body, ChatCompletionResponse::class.java)
        parsed.error?.let {
            throw LlmException(
                message = "OpenRouter отказал: ${it.message ?: "причина не указана"}",
                providerStatus = it.code,
                providerBody = response.body,
            )
        }

        val choice = parsed.choices.firstOrNull()
            ?: throw LlmException("OpenRouter не вернул ни одного варианта ответа", providerBody = response.body)
        val answer = choice.message?.content?.takeIf { it.isNotBlank() }
            ?: throw LlmException("OpenRouter вернул ответ без текста", providerBody = response.body)

        return LlmCompletion(
            content = answer.trim(),
            reasoning = choice.message.reasoning?.takeIf { it.isNotBlank() }
                ?: choice.message.reasoningContent?.takeIf { it.isNotBlank() },
            model = parsed.model,
            provider = parsed.provider ?: "openrouter",
            finishReason = choice.finishReason ?: choice.nativeFinishReason,
            usage = parsed.usage?.toTokenUsage(),
        )
    }

    private fun Usage.toTokenUsage() = TokenUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        cachedPromptTokens = promptTokensDetails?.cachedTokens ?: 0,
        reasoningTokens = completionTokensDetails?.reasoningTokens ?: 0,
        costUsd = cost,
        costSource = if (cost == null) CostSource.UNKNOWN else CostSource.PROVIDER,
    )

    private fun errorMessage(status: Int, body: String): String {
        val detail = extractMessage(body)
        val prefix = when (status) {
            400 -> "OpenRouter отклонил запрос (400)."
            401 -> "OpenRouter отклонил ключ (401). Проверьте OPENROUTER_API_KEY."
            402 -> "Недостаточно средств на балансе OpenRouter (402)."
            403 -> "Запрос отклонен модерацией провайдера (403)."
            404 -> "Такой модели у OpenRouter нет (404)."
            408 -> "Модель не ответила за отведенное время (408)."
            429 -> "Превышен лимит запросов OpenRouter (429)."
            502 -> "Площадка под моделью недоступна (502)."
            503 -> "Нет доступной площадки под эту модель (503)."
            in 500..599 -> "OpenRouter временно недоступен ($status)."
            else -> "OpenRouter вернул ошибку $status."
        }
        return if (detail == null) prefix else "$prefix $detail"
    }

    private fun extractMessage(body: String): String? = runCatching {
        objectMapper.readTree(body)["error"]?.get("message")?.asString()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private companion object {
        const val COMPLETIONS_PATH = "/chat/completions"
    }
}

package advent.posts.llm

import advent.posts.config.LlmProperties
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import java.net.URI

/** OpenAI-совместимый клиент DeepSeek для редактора постов. */
class DeepSeekClient(
    private val properties: LlmProperties,
    private val objectMapper: ObjectMapper,
    private val http: HttpJson = HttpJson(properties.connectTimeout),
) : LlmClient {
    private val log = LoggerFactory.getLogger(javaClass)
    private val endpoint: URI = URI.create(properties.baseUrl.trimEnd('/') + COMPLETIONS_PATH)

    override fun complete(request: ChatCompletionRequest): LlmCompletion {
        if (properties.apiKey.isBlank()) {
            throw LlmException("Не задан API-ключ: переменная окружения DEEPSEEK_API_KEY пуста")
        }

        // DeepSeek предупреждает, что в JSON-режиме ответ изредка приходит пустым; повтор это лечит.
        val body = objectMapper.writeValueAsString(request)
        var blankBody = ""
        for (attempt in 1..BLANK_ATTEMPTS) {
            if (attempt > 1) log.warn("DeepSeek вернул пустой ответ, повтор {} из {}", attempt, BLANK_ATTEMPTS)
            val response = post(body)
            completionOf(response)?.let { return it }
            blankBody = response.body
        }
        throw LlmException("DeepSeek вернул ответ без текста", providerBody = blankBody)
    }

    private fun post(body: String): HttpJson.Result {
        val response = http.post(
            endpoint = endpoint,
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "Authorization" to "Bearer ${properties.apiKey}",
            ),
            body = body,
            readTimeout = properties.readTimeout,
            log = log,
        )
        if (response.status >= 400) {
            log.warn("DeepSeek ответил ошибкой {}: {}", response.status, response.body.take(500))
            throw LlmException(
                message = errorMessage(response.status, response.body),
                providerStatus = response.status,
                providerBody = response.body,
            )
        }
        return response
    }

    /** Разобранный ответ или null, если текст состоит из одних пробелов. */
    private fun completionOf(response: HttpJson.Result): LlmCompletion? {
        val parsed = objectMapper.readValue(response.body, ChatCompletionResponse::class.java)
        val choice = parsed.choices.firstOrNull()
            ?: throw LlmException("DeepSeek не вернул ни одного варианта ответа", providerBody = response.body)
        val answer = choice.message?.content?.takeIf { it.isNotBlank() } ?: return null
        val usage = parsed.usage?.let { TokenUsage(it.promptTokens, it.completionTokens, it.promptCacheHitTokens) }
        log.info(
            "DeepSeek {}: {} токенов на входе ({} из кэша), {} на выходе, finish_reason {}",
            parsed.model, usage?.promptTokens, usage?.cachedPromptTokens, usage?.completionTokens, choice.finishReason,
        )
        return LlmCompletion(
            content = answer.trim(),
            model = parsed.model,
            finishReason = choice.finishReason,
            usage = usage,
        )
    }

    private fun errorMessage(status: Int, body: String): String {
        val detail = extractMessage(body)
        val prefix = when (status) {
            400 -> "DeepSeek отклонил запрос (400)."
            401 -> "DeepSeek отклонил ключ (401). Проверьте DEEPSEEK_API_KEY."
            402 -> "Недостаточно средств на балансе DeepSeek (402)."
            422 -> "DeepSeek не принял параметры запроса (422)."
            429 -> "Превышен лимит запросов DeepSeek (429)."
            in 500..599 -> "DeepSeek временно недоступен ($status)."
            else -> "DeepSeek вернул ошибку $status."
        }
        return if (detail == null) prefix else "$prefix $detail"
    }

    private fun extractMessage(body: String): String? = runCatching {
        objectMapper.readTree(body)["error"]?.get("message")?.asString()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private companion object {
        const val COMPLETIONS_PATH = "/chat/completions"
        const val BLANK_ATTEMPTS = 2
    }
}

package advent.day6.llm

import advent.day6.config.DeepSeekProperties
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse
import tools.jackson.databind.ObjectMapper

/**
 * Тонкая обёртка над HTTP API провайдера. Единственное место в приложении, которое знает
 * про сеть и API-ключ.
 *
 * Ответ читается как поток server-sent events: строки `data: {...}` разбираются по одной,
 * и каждый кусочек текста немедленно отдаётся вызывающему. Агент благодаря этому «говорит»
 * по мере генерации, а не молчит до конца.
 */
@Service
class DeepSeekClient(
    private val deepSeekRestClient: RestClient,
    private val properties: DeepSeekProperties,
    private val objectMapper: ObjectMapper,
) : LlmClient {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun stream(request: ChatCompletionRequest, onDelta: (LlmDelta) -> Unit): LlmCompletion {
        if (properties.apiKey.isBlank()) {
            throw LlmException("Не задан API-ключ: переменная окружения DEEPSEEK_API_KEY пуста")
        }

        val body = objectMapper.writeValueAsString(request)
        var delivered = false
        val relay: (LlmDelta) -> Unit = { delivered = true; onDelta(it) }

        return try {
            send(body, relay)
        } catch (e: ResourceAccessException) {
            // Обрыв на подключении лечится повтором. Но если наружу уже ушёл хотя бы один
            // токен, повторять нельзя: пользователь получил бы два ответа, склеенных в один.
            if (delivered) throw LlmException("Связь с LLM оборвалась посреди ответа: ${e.message}", cause = e)
            log.warn("Сетевой сбой при обращении к LLM, повторяю: {}", e.message)
            try {
                send(body, relay)
            } catch (retry: ResourceAccessException) {
                throw LlmException("Не удалось получить ответ от LLM: ${retry.message}", cause = retry)
            }
        }
    }

    private fun send(body: String, onDelta: (LlmDelta) -> Unit): LlmCompletion =
        deepSeekRestClient.post()
            .uri(COMPLETIONS_PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body(body)
            .exchange { _, response -> readStream(response, onDelta) }

    private fun readStream(response: ConvertibleClientHttpResponse, onDelta: (LlmDelta) -> Unit): LlmCompletion {
        val status = response.statusCode
        if (status.isError) {
            val raw = response.bodyTo(String::class.java).orEmpty()
            log.warn("LLM ответил ошибкой {}: {}", status, raw.take(500))
            throw LlmException(providerErrorMessage(status.value(), raw), providerStatus = status)
        }

        val content = StringBuilder()
        val reasoning = StringBuilder()
        var model: String? = null
        var finishReason: String? = null
        var usage: Usage? = null

        response.body.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (!line.startsWith(DATA_PREFIX)) continue
                val payload = line.removePrefix(DATA_PREFIX).trim()
                if (payload == DONE_MARKER) break

                val chunk = objectMapper.readValue(payload, StreamChunk::class.java)
                chunk.model?.let { model = it }
                chunk.usage?.let { usage = it }

                for (choice in chunk.choices) {
                    choice.finishReason?.let { finishReason = it }
                    val delta = choice.delta ?: continue
                    if (delta.content.isNullOrEmpty() && delta.reasoningContent.isNullOrEmpty()) continue

                    delta.content?.let(content::append)
                    delta.reasoningContent?.let(reasoning::append)
                    onDelta(LlmDelta(content = delta.content, reasoning = delta.reasoningContent))
                }
            }
        }

        if (content.isEmpty()) throw LlmException("LLM вернул ответ без текста")

        log.info(
            "LLM ok: model={}, finish={}, tokens={}",
            model, finishReason, usage?.totalTokens,
        )

        return LlmCompletion(
            content = content.toString(),
            reasoning = reasoning.toString().takeIf { it.isNotBlank() },
            model = model,
            finishReason = finishReason,
            usage = usage,
        )
    }

    private fun providerErrorMessage(status: Int, body: String): String = when (status) {
        401 -> "LLM отклонил ключ (401). Проверьте DEEPSEEK_API_KEY."
        402 -> "Недостаточно средств на балансе LLM-провайдера (402)."
        422 -> "LLM отклонил параметры запроса (422): ${body.take(300)}"
        429 -> "Превышен лимит запросов к LLM (429). Попробуйте позже."
        in 500..599 -> "LLM временно недоступен ($status). Попробуйте позже."
        else -> "LLM вернул ошибку $status: ${body.take(300)}"
    }

    private companion object {
        const val COMPLETIONS_PATH = "/chat/completions"
        const val DATA_PREFIX = "data:"
        const val DONE_MARKER = "[DONE]"
    }
}

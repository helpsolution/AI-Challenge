package advent.day6.llm

import advent.day6.config.DeepSeekProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets.UTF_8

/**
 * Тонкая обёртка над HTTP API провайдера. Единственное место в приложении, которое знает
 * про сеть и API-ключ.
 *
 * Ответ читается как поток server-sent events: строки `data: {...}` разбираются по одной,
 * и каждый кусочек текста немедленно отдаётся вызывающему. Агент благодаря этому «говорит»
 * по мере генерации, а не молчит до конца.
 *
 * Здесь взят `java.net.http.HttpClient` напрямую, без RestClient: тело запроса собирается
 * строкой, ответ читается потоком, и разбирать за нас нечего. Решающий довод — таймаут.
 * `RestClient` с `JdkClientHttpRequestFactory` ожидание ответа не ограничивал: запрос,
 * на который провайдер не ответил, висел дольше заданных трёх минут и держал агента
 * заблокированным навсегда. У `HttpRequest.timeout` эта граница своя и срабатывает.
 */
@Service
class DeepSeekClient(
    private val properties: DeepSeekProperties,
    private val objectMapper: ObjectMapper,
) : LlmClient {
    private val log = LoggerFactory.getLogger(javaClass)

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val endpoint: URI = URI.create(properties.baseUrl.trimEnd('/') + COMPLETIONS_PATH)

    override fun stream(request: ChatCompletionRequest, onDelta: (LlmDelta) -> Unit): LlmCompletion {
        if (properties.apiKey.isBlank()) {
            throw LlmException("Не задан API-ключ: переменная окружения DEEPSEEK_API_KEY пуста")
        }

        val body = objectMapper.writeValueAsString(request)
        var delivered = false
        val relay: (LlmDelta) -> Unit = { delivered = true; onDelta(it) }

        return try {
            send(body, relay)
        } catch (e: HttpTimeoutException) {
            // Повторять таймаут смысла нет: второе ожидание будет таким же долгим.
            throw LlmException("LLM не ответил за ${properties.readTimeout.toSeconds()} с", cause = e)
        } catch (e: IOException) {
            // Обрыв соединения лечится повтором. Но если наружу уже ушёл хотя бы один токен,
            // повторять нельзя: пользователь получил бы два ответа, склеенных в один.
            if (delivered) throw LlmException("Связь с LLM оборвалась посреди ответа: ${e.message}", cause = e)
            log.warn("Сетевой сбой при обращении к LLM, повторяю: {}", e.message)
            try {
                send(body, relay)
            } catch (retry: IOException) {
                throw LlmException("Не удалось получить ответ от LLM: ${retry.message}", cause = retry)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw LlmException("Обращение к LLM прервано", cause = e)
        }
    }

    private fun send(body: String, onDelta: (LlmDelta) -> Unit): LlmCompletion {
        val httpRequest = HttpRequest.newBuilder(endpoint)
            // Граница ожидания ответа. Без неё повисший запрос держит агента навсегда.
            .timeout(properties.readTimeout)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("Authorization", "Bearer ${properties.apiKey}")
            .POST(HttpRequest.BodyPublishers.ofString(body, UTF_8))
            .build()

        val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
        val status = response.statusCode()

        if (status >= 400) {
            val raw = response.body().use { it.readNBytes(ERROR_BODY_LIMIT).toString(UTF_8) }
            log.warn("LLM ответил ошибкой {}: {}", status, raw.take(500))
            throw LlmException(providerErrorMessage(status, raw), providerStatus = status)
        }

        return response.body().use { readStream(it.bufferedReader(), onDelta) }
    }

    private fun readStream(reader: java.io.BufferedReader, onDelta: (LlmDelta) -> Unit): LlmCompletion {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        var model: String? = null
        var finishReason: String? = null
        var usage: Usage? = null

        reader.useLines { lines ->
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

        log.info("LLM ok: model={}, finish={}, tokens={}", model, finishReason, usage?.totalTokens)

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
        /** Тело ошибки читаем ограниченно: в сообщение всё равно попадёт только начало. */
        const val ERROR_BODY_LIMIT = 8 * 1024
    }
}

package advent.day7.llm

import advent.day7.config.DeepSeekProperties
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
 * про сеть и API-ключ: отправить JSON, получить JSON, разобрать.
 *
 * Взят `java.net.http.HttpClient` напрямую, без RestClient. Дело в таймауте: `RestClient`
 * с `JdkClientHttpRequestFactory` ожидание ответа не ограничивал — запрос, на который
 * провайдер не ответил, висел дольше заданных трёх минут и держал агента заблокированным
 * навсегда. У `HttpRequest.timeout` эта граница своя и срабатывает.
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

    override fun complete(request: ChatCompletionRequest): LlmCompletion {
        if (properties.apiKey.isBlank()) {
            throw LlmException("Не задан API-ключ: переменная окружения DEEPSEEK_API_KEY пуста")
        }

        val body = objectMapper.writeValueAsString(request)

        return try {
            send(body)
        } catch (e: HttpTimeoutException) {
            // Повторять таймаут смысла нет: второе ожидание будет таким же долгим.
            throw LlmException("LLM не ответил за ${properties.readTimeout.toSeconds()} с", cause = e)
        } catch (e: IOException) {
            // Обрыв соединения лечится повтором: ответа мы не получили, дублировать нечего.
            log.warn("Сетевой сбой при обращении к LLM, повторяю: {}", e.message)
            try {
                send(body)
            } catch (retry: IOException) {
                throw LlmException("Не удалось получить ответ от LLM: ${retry.message}", cause = retry)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw LlmException("Обращение к LLM прервано", cause = e)
        }
    }

    private fun send(body: String): LlmCompletion {
        val httpRequest = HttpRequest.newBuilder(endpoint)
            // Граница ожидания ответа. Без неё повисший запрос держит агента навсегда.
            .timeout(properties.readTimeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer ${properties.apiKey}")
            .POST(HttpRequest.BodyPublishers.ofString(body, UTF_8))
            .build()

        // Сырые байты запроса и ответа — самый нижний уровень: что именно ушло в сеть.
        // Логируем только тело. Заголовки не логируем никогда: в них лежит API-ключ.
        log.trace("HTTP POST {} — тело запроса:\n{}", endpoint, body)

        val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(UTF_8))
        val status = response.statusCode()
        val raw = response.body().orEmpty()
        log.trace("HTTP {} — тело ответа:\n{}", status, raw)

        if (status >= 400) {
            log.warn("LLM ответил ошибкой {}: {}", status, raw.take(500))
            throw LlmException(providerErrorMessage(status, raw), providerStatus = status)
        }

        val parsed = objectMapper.readValue(raw, ChatCompletionResponse::class.java)
        val choice = parsed.choices.firstOrNull()
            ?: throw LlmException("LLM не вернул ни одного варианта ответа")
        val answer = choice.message?.content?.takeIf { it.isNotBlank() }
            ?: throw LlmException("LLM вернул ответ без текста")

        log.info(
            "LLM ok: model={}, finish={}, tokens={}",
            parsed.model, choice.finishReason, parsed.usage?.totalTokens,
        )

        return LlmCompletion(
            content = answer.trim(),
            reasoning = choice.message.reasoningContent?.takeIf { it.isNotBlank() },
            model = parsed.model,
            finishReason = choice.finishReason,
            usage = parsed.usage,
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
    }
}

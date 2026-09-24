package advent.pipeline.llm

import advent.pipeline.config.LlmProperties
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class ChatMessage(val role: String, val content: String?)

private data class ChatRequest(val model: String, val messages: List<ChatMessage>, val temperature: Double)

private data class ChatResponse(val choices: List<ChatChoice>)

private data class ChatChoice(val message: ChatMessage)

class LlmException(message: String) : RuntimeException(message)

/**
 * Клиент DeepSeek (OpenAI-совместимый chat completions) для шага summarize.
 * Инструментов здесь нет: модель получает статьи и возвращает текст — это обычная функция «текст → текст».
 */
@Component
class LlmClient(private val properties: LlmProperties, private val json: JsonMapper) {
    private val http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()

    fun complete(system: String, user: String): String {
        val body = json.writeValueAsString(
            ChatRequest(
                model = properties.model,
                messages = listOf(ChatMessage("system", system), ChatMessage("user", user)),
                temperature = TEMPERATURE,
            ),
        )
        val request = HttpRequest.newBuilder(URI(properties.baseUrl.trimEnd('/') + COMPLETIONS_PATH))
            .timeout(properties.timeout)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${properties.apiKey}")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw LlmException("DeepSeek недоступен: ${e.message ?: e::class.simpleName}")
        }
        val text = response.body()
        // Ответ провайдера показываем как есть: по пересказу не понять, что именно пошло не так.
        if (response.statusCode() >= 400) throw LlmException("DeepSeek ответил ${response.statusCode()}: ${text.take(300)}")

        val parsed = try {
            json.readValue(text, ChatResponse::class.java)
        } catch (e: JacksonException) {
            throw LlmException("Ответ DeepSeek не разобран: ${text.take(300)}")
        }
        return parsed.choices.firstOrNull()?.message?.content?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw LlmException("DeepSeek вернул пустой ответ")
    }

    private companion object {
        const val COMPLETIONS_PATH = "/chat/completions"
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        const val TEMPERATURE = 0.2
    }
}

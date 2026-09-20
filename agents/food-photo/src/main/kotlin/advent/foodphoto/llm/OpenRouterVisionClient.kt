package advent.foodphoto.llm

import advent.foodphoto.config.OpenRouterProperties
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

@Component
class OpenRouterVisionClient(
    private val properties: OpenRouterProperties,
    private val objectMapper: ObjectMapper,
) : VisionClient {
    private val endpoint = URI.create(properties.baseUrl.trimEnd('/') + "/chat/completions")
    private val http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build()

    override fun describe(image: ByteArray, mimeType: String): String {
        if (properties.apiKey.isBlank()) throw LlmException("Не задан OPENROUTER_API_KEY")

        val imageUrl = "data:$mimeType;base64,${Base64.getEncoder().encodeToString(image)}"
        val body = objectMapper.writeValueAsString(
            mapOf(
                "model" to properties.model,
                "messages" to listOf(
                    mapOf(
                        "role" to "user",
                        "content" to listOf(
                            mapOf(
                                "type" to "text",
                                "text" to """
                                    Опиши еду на фото по-русски. Верни только JSON-объект с полями:
                                    dishName (короткое название блюда или null, если еду определить нельзя),
                                    description (1–2 предложения только о том, что видно),
                                    likelyIngredients (массив лишь вероятных видимых ингредиентов).
                                    Если еда не видна, скажи об этом в description и верни пустой массив.
                                    Не утверждай точный состав, вес, калории или способ приготовления по одному фото.
                                """.trimIndent(),
                            ),
                            mapOf("type" to "image_url", "image_url" to mapOf("url" to imageUrl)),
                        ),
                    ),
                ),
                "max_tokens" to properties.maxTokens,
                "response_format" to mapOf("type" to "json_object"),
            ),
        )
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(properties.readTimeout)
            .header("Authorization", "Bearer ${properties.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, UTF_8))
            .build()

        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString(UTF_8))
        } catch (e: HttpTimeoutException) {
            throw LlmException("OpenRouter не ответил за ${properties.readTimeout.toSeconds()} секунд", e)
        } catch (e: IOException) {
            throw LlmException("Сетевая ошибка при обращении к OpenRouter", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw LlmException("Обращение к OpenRouter прервано", e)
        }

        if (response.statusCode() !in 200..299) {
            val detail = when (response.statusCode()) {
                401 -> "Проверьте OPENROUTER_API_KEY"
                402 -> "Недостаточно средств на балансе"
                404 -> "Модель не найдена"
                429 -> "Превышен лимит запросов"
                else -> "Проверьте модель и доступность сервиса"
            }
            throw LlmException("OpenRouter вернул HTTP ${response.statusCode()}. $detail")
        }

        val completion = try {
            objectMapper.readValue(response.body(), CompletionResponse::class.java)
        } catch (e: Exception) {
            throw LlmException("OpenRouter вернул некорректный ответ", e)
        }
        val choice = completion.choices.firstOrNull()
            ?: throw LlmException("OpenRouter не вернул ответ")
        if (choice.finishReason == "length") throw LlmException("Ответ модели обрезан: увеличьте OPENROUTER_MAX_TOKENS")
        return choice.message?.content?.takeIf { it.isNotBlank() }
            ?: throw LlmException("OpenRouter вернул пустой ответ")
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class CompletionResponse(val choices: List<CompletionChoice> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class CompletionChoice(
        val message: CompletionMessage? = null,
        @param:JsonProperty("finish_reason") val finishReason: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class CompletionMessage(val content: String? = null)
}

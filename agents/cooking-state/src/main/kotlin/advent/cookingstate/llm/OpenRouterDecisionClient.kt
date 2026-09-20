package advent.cookingstate.llm

import advent.cookingstate.agent.AgentDecision
import advent.cookingstate.agent.CookingSession
import advent.cookingstate.config.OpenRouterProperties
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

@Component
class OpenRouterDecisionClient(
    private val properties: OpenRouterProperties,
    private val mapper: ObjectMapper,
) : DecisionClient {
    private val endpoint = URI.create(properties.baseUrl.trimEnd('/') + "/chat/completions")
    private val http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build()

    override fun decide(session: CookingSession, message: String): AgentDecision {
        if (properties.apiKey.isBlank()) throw LlmException("Не задан OPENROUTER_API_KEY")
        val instructions = """
            Ты агент «Что приготовить?». Пять этапов:
            GATHERING — сбор продуктов и времени;
            CHOOSING — выбор из 2–3 предложенных блюд;
            READY — одно блюдо выбрано, рецепт показан, ждём команды начать;
            COOKING — показываем текущий шаг и ждём подтверждения его выполнения;
            DONE — все шаги завершены.
            После каждого сообщения верни только JSON с полями:
            nextState: "GATHERING", "CHOOSING", "READY", "COOKING" или "DONE";
            ingredients: полный актуальный массив названий продуктов;
            timeMinutes: целое число минут или null;
            servings: целое число порций или null;
            restrictions: полный актуальный массив пищевых ограничений;
            suggestions: массив объектов {"name": "...", "reason": "..."};
            recipe: объект {"name": "точное название выбранного варианта", "steps": ["шаг 1", "шаг 2", ...]} или null;
            advanceStep: true или false;
            reply: короткий ответ пользователю на русском.
            Сохраняй прежние данные, если пользователь их не меняет. Учитывай исправления и удаление продуктов.
            Переходи в CHOOSING, когда известны хотя бы один продукт и время. Тогда предложи 2–3 разных блюда,
            подходящих к продуктам, времени и ограничениям, и попроси выбрать. Используй только перечисленные
            пользователем продукты: не предлагай гренки без хлеба, пасту без пасты и блюда с другими
            неуказанными обязательными ингредиентами. Если вариантов мало, предложи разные способы приготовить
            имеющиеся продукты. В reason кратко объясни, почему хватает именно этих продуктов.
            Если данных недостаточно, оставайся в GATHERING, suggestions делай пустым и задай один уточняющий вопрос.
            Если пользователь меняет условия в CHOOSING, обнови предложения. Если данных не хватает, вернись в GATHERING.
            В CHOOSING, когда пользователь явно выбирает один из показанных вариантов, переходи в READY.
            Создай recipe с ТОЧНЫМ названием этого варианта и 2–6 короткими последовательными шагами.
            Используй доступные продукты и соблюдай ограничения. В reply кратко покажи рецепт и спроси, начинать ли.
            В READY при вопросе оставайся в READY. По команде «начинаем» переходи в COOKING, advanceStep=false.
            В COOKING при вопросе о текущем шаге оставайся в COOKING, advanceStep=false и ответь на вопрос.
            Только если пользователь явно сообщает, что выполнил текущий шаг («готово», «сделал»), ставь advanceStep=true.
            Если это не последний шаг, оставайся в COOKING; если последний — переходи в DONE.
            Текущий шаг смотри в context.currentStepIndex (нумерация с нуля), число шагов — в context.recipe.steps.
            В DONE предложи новый цикл. По «начать заново» переходи в GATHERING и очисти прежние данные.
            Если до или во время готовки пользователь меняет продукты/время/ограничения и прежний рецепт уже не подходит,
            вернись в CHOOSING с новыми вариантами либо в GATHERING, если данных недостаточно.
            Не перескакивай этапы. Не считай вопрос или благодарность подтверждением выполнения шага.
            Во всех ответах верни все перечисленные поля; если блюдо ещё не выбрано, recipe=null.
            Ответ не должен содержать поля вне этой схемы.
        """.trimIndent()
        val body = mapper.writeValueAsString(mapOf(
            "model" to properties.model,
            "messages" to listOf(
                mapOf("role" to "system", "content" to instructions),
                mapOf("role" to "user", "content" to "Текущее состояние JSON: ${mapper.writeValueAsString(session)}\nНовое сообщение: $message"),
            ),
            "max_tokens" to properties.maxTokens,
            "response_format" to mapOf("type" to "json_object"),
        ))
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
        return try {
            val completion = mapper.readValue(response.body(), CompletionResponse::class.java)
            val choice = completion.choices.firstOrNull() ?: throw LlmException("OpenRouter не вернул ответ")
            if (choice.finishReason == "length") throw LlmException("Ответ модели обрезан: увеличьте OPENROUTER_MAX_TOKENS")
            mapper.readValue(choice.message?.content ?: throw LlmException("OpenRouter вернул пустой ответ"), AgentDecision::class.java)
        } catch (e: LlmException) {
            throw e
        } catch (e: Exception) {
            throw LlmException("Модель вернула некорректное решение", e)
        }
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

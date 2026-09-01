package advent.day2

import advent.day2.llm.ApiMessage
import advent.day2.llm.ChatCompletionRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Провайдер ждёт snake_case и не терпит лишних null-полей — фиксируем формат тестом,
 * чтобы переименование поля в Kotlin не сломало интеграцию молча.
 */
@SpringBootTest
class LlmRequestSerializationTest {

    @Autowired lateinit var objectMapper: ObjectMapper

    @Test
    fun `имена полей соответствуют схеме API, пустые параметры не сериализуются`() {
        val json = objectMapper.writeValueAsString(
            ChatCompletionRequest(
                model = "deepseek-chat",
                messages = listOf(ApiMessage(role = "user", content = "Привет")),
                maxTokens = 100,
                topP = 0.9,
                frequencyPenalty = 0.5,
                presencePenalty = -0.5,
            ),
        )

        assertTrue(json.contains("\"max_tokens\":100"), json)
        assertTrue(json.contains("\"top_p\":0.9"), json)
        assertTrue(json.contains("\"frequency_penalty\":0.5"), json)
        assertTrue(json.contains("\"presence_penalty\":-0.5"), json)
        assertFalse(json.contains("temperature"), "не заданный параметр не должен попадать в тело запроса")
        assertFalse(json.contains("null"), json)
        assertTrue(json.contains("\"stream\":false"))
    }

    @Test
    fun `ответ провайдера разбирается в доменную модель`() {
        val response = objectMapper.readValue(
            """
            {"id":"x","model":"deepseek-chat","choices":[{"index":0,
             "message":{"role":"assistant","content":"привет","reasoning_content":"думаю"},
             "finish_reason":"stop"}],
             "usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8},
             "system_fingerprint":"неизвестное поле"}
            """.trimIndent(),
            advent.day2.llm.ChatCompletionResponse::class.java,
        )

        assertEquals("привет", response.choices.first().message?.content)
        assertEquals("думаю", response.choices.first().message?.reasoningContent)
        assertEquals(8, response.usage?.totalTokens)
    }
}

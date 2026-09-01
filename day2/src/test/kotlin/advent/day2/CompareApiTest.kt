package advent.day2

import advent.day2.llm.ApiMessage
import advent.day2.llm.ChatCompletionRequest
import advent.day2.llm.ChatCompletionResponse
import advent.day2.llm.Choice
import advent.day2.llm.LlmClient
import advent.day2.llm.LlmExchange
import advent.day2.llm.Usage
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Стаб провайдера: без системной инструкции отвечает свободным текстом,
 * с инструкцией — JSON. Так воспроизводится ровно то поведение,
 * ради которого задание и делается.
 */
class ScriptedLlmClient : LlmClient {
    val requests = CopyOnWriteArrayList<ChatCompletionRequest>()
    private val counter = AtomicInteger()

    override fun complete(request: ChatCompletionRequest): LlmExchange {
        requests += request
        val hasSystem = request.messages.any { it.role == "system" }
        val n = counter.incrementAndGet()
        val content = if (hasSystem) {
            """{"dish":"борщ $n","ingredients":[{"name":"свёкла","weightGrams":$n}]}"""
        } else {
            "Конечно! Вот подробный рецепт борща, начнём с истории этого блюда и списка продуктов."
        }
        val parsed = ChatCompletionResponse(
            id = "test-$n",
            model = request.model,
            choices = listOf(Choice(message = ApiMessage(role = "assistant", content = content), finishReason = "stop")),
            usage = Usage(promptTokens = 10, completionTokens = 20, totalTokens = 30),
        )
        return LlmExchange(
            url = "https://api.deepseek.com/chat/completions",
            method = "POST",
            requestHeaders = mapOf("Authorization" to "Bearer sk-tes…4242"),
            requestBody = """{"model":"${request.model}"}""",
            status = 200,
            responseBody = """{"choices":[{"message":{"content":${'"'}…${'"'}}}]}""",
            parsed = parsed,
        )
    }
}

@SpringBootTest
@AutoConfigureMockMvc
class CompareApiTest {

    @TestConfiguration
    class Stubs {
        @Bean
        @Primary
        fun scriptedLlmClient() = ScriptedLlmClient()
    }

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var llmClient: ScriptedLlmClient

    private val jsonConstraints = """
        "constraints": {
          "format": "JSON",
          "jsonSchema": "{\"dish\":\"string\",\"ingredients\":[{\"name\":\"string\",\"weightGrams\":\"number\"}]}",
          "maxWords": 50,
          "maxTokens": 300,
          "stopSequence": "###"
        }
    """.trimIndent()

    @Test
    fun `сравнение возвращает два прогона и показывает разницу в соблюдении формата`() {
        llmClient.requests.clear()

        mockMvc.perform(
            post("/api/compare").contentType(MediaType.APPLICATION_JSON)
                .content("""{"prompt":"Дай рецепт борща", $jsonConstraints}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.systemPrompt").isNotEmpty)
            .andExpect(jsonPath("$.baseline.compliance.passed").value(false))
            .andExpect(jsonPath("$.baseline.compliance.jsonValid").value(false))
            .andExpect(jsonPath("$.constrained.compliance.passed").value(true))
            .andExpect(jsonPath("$.constrained.compliance.jsonValid").value(true))
            .andExpect(jsonPath("$.constrained.compliance.structureSignature").isNotEmpty)

        val baseline = llmClient.requests.single { r -> r.messages.none { it.role == "system" } }
        val constrained = llmClient.requests.single { r -> r.messages.any { it.role == "system" } }

        assertNull(baseline.maxTokens, "эталонный прогон идёт без потолка длины")
        assertNull(baseline.stop, "эталонный прогон идёт без стоп-последовательности")
        assertEquals(300, constrained.maxTokens)
        assertEquals(listOf("###"), constrained.stop)
    }

    @Test
    fun `JSON-режим API включается только для формата JSON`() {
        llmClient.requests.clear()

        mockMvc.perform(
            post("/api/compare").contentType(MediaType.APPLICATION_JSON)
                .content("""{"prompt":"тест","constraints":{"format":"JSON","jsonMode":true}}"""),
        ).andExpect(status().isOk)

        val constrained = llmClient.requests.single { r -> r.messages.any { it.role == "system" } }
        assertEquals("json_object", constrained.responseFormat?.type)

        llmClient.requests.clear()
        mockMvc.perform(
            post("/api/compare").contentType(MediaType.APPLICATION_JSON)
                .content("""{"prompt":"тест","constraints":{"format":"BULLETS","jsonMode":true,"maxItems":3}}"""),
        ).andExpect(status().isOk)

        val bullets = llmClient.requests.single { r -> r.messages.any { it.role == "system" } }
        assertNull(bullets.responseFormat, "вне JSON-формата response_format не имеет смысла")
    }

    @Test
    fun `проверка стабильности сравнивает структуру, а не значения`() {
        mockMvc.perform(
            post("/api/determinism").contentType(MediaType.APPLICATION_JSON)
                .content("""{"prompt":"Дай рецепт борща","runs":3, $jsonConstraints}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.runs").value(3))
            .andExpect(jsonPath("$.results.length()").value(3))
            .andExpect(jsonPath("$.structureGroups.length()").value(1))
            .andExpect(jsonPath("$.structureGroups[0].count").value(3))
            .andExpect(jsonPath("$.structureIdentical").value(true))
            .andExpect(jsonPath("$.allPassed").value(true))
    }

    @Test
    fun `число прогонов ограничено сверху`() {
        mockMvc.perform(
            post("/api/determinism").contentType(MediaType.APPLICATION_JSON)
                .content("""{"prompt":"тест","runs":50}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.details[0]").value("runs: Прогонов: максимум 8"))
    }

    @Test
    fun `запрос без ограничений всё равно отрабатывает`() {
        mockMvc.perform(
            post("/api/compare").contentType(MediaType.APPLICATION_JSON)
                .content("""{"prompt":"привет"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.systemPrompt").doesNotExist())
            .andExpect(jsonPath("$.baseline.compliance.passed").value(true))
            .andExpect(jsonPath("$.constrained.compliance.passed").value(true))

        assertTrue(llmClient.requests.isNotEmpty())
    }
}

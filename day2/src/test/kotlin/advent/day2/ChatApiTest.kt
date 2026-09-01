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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Запоминает последний запрос к LLM и отдаёт заранее заготовленный обмен. */
class RecordingLlmClient : LlmClient {
    var lastRequest: ChatCompletionRequest? = null

    override fun complete(request: ChatCompletionRequest): LlmExchange {
        lastRequest = request
        val parsed = ChatCompletionResponse(
            id = "test-1",
            model = request.model,
            choices = listOf(
                Choice(message = ApiMessage(role = "assistant", content = "42"), finishReason = "stop"),
            ),
            usage = Usage(promptTokens = 10, completionTokens = 2, totalTokens = 12),
        )
        return LlmExchange(
            url = "https://api.deepseek.com/chat/completions",
            method = "POST",
            requestHeaders = mapOf(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer sk-tes…4242",
            ),
            requestBody = """{"model":"${request.model}","stream":false}""",
            status = 200,
            responseBody = """{"id":"test-1","choices":[{"message":{"content":"42"}}]}""",
            parsed = parsed,
        )
    }
}

@SpringBootTest
@AutoConfigureMockMvc
class ChatApiTest {

    @TestConfiguration
    class Stubs {
        @Bean
        @Primary
        fun recordingLlmClient() = RecordingLlmClient()
    }

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var llmClient: RecordingLlmClient

    @Test
    fun `без параметров запрос уходит на дефолтах провайдера`() {
        mockMvc.perform(
            post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content("""{"prompt":"Сколько будет 6*7?"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.answer").value("42"))
            .andExpect(jsonPath("$.usage.totalTokens").value(12))
            .andExpect(jsonPath("$.appliedParams").isEmpty)

        val sent = llmClient.lastRequest!!
        assertEquals("deepseek-chat", sent.model)
        assertNull(sent.temperature, "temperature не должен уходить, если пользователь его не задал")
        assertNull(sent.maxTokens)
        assertNull(sent.topP)
    }

    @Test
    fun `продвинутый режим прокидывает только включённые параметры`() {
        mockMvc.perform(
            post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"prompt":"Привет","model":"deepseek-reasoner",
                     "params":{"temperature":0.3,"maxTokens":256,"stop":["###"]}}
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.appliedParams.temperature").value(0.3))
            .andExpect(jsonPath("$.appliedParams.max_tokens").value(256))

        val sent = llmClient.lastRequest!!
        assertEquals("deepseek-reasoner", sent.model)
        assertEquals(0.3, sent.temperature)
        assertEquals(256, sent.maxTokens)
        assertEquals(listOf("###"), sent.stop)
        assertNull(sent.topP, "выключенный параметр не должен уходить в API")
    }

    @Test
    fun `ответ содержит сырой обмен с провайдером`() {
        mockMvc.perform(
            post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content("""{"prompt":"Привет"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.exchange.method").value("POST"))
            .andExpect(jsonPath("$.exchange.url").value("https://api.deepseek.com/chat/completions"))
            .andExpect(jsonPath("$.exchange.status").value(200))
            .andExpect(jsonPath("$.exchange.requestBody").isNotEmpty)
            .andExpect(jsonPath("$.exchange.responseBody").isNotEmpty)
            .andExpect(jsonPath("$.exchange.requestHeaders.Authorization").value("Bearer sk-tes…4242"))
    }

    @Test
    fun `параметр вне допустимого диапазона отклоняется до похода в сеть`() {
        mockMvc.perform(
            post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content("""{"prompt":"Привет","params":{"temperature":5.0}}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.details[0]").value("params.temperature: temperature: максимум 2.0"))
    }

    @Test
    fun `пустой запрос отклоняется`() {
        mockMvc.perform(
            post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content("""{"prompt":"   "}"""),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `неизвестная модель отклоняется`() {
        mockMvc.perform(
            post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content("""{"prompt":"Привет","model":"gpt-4o"}"""),
        )
            .andExpect(status().isBadRequest)
    }
}

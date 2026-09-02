package advent.day3

import advent.day3.llm.ApiMessage
import advent.day3.llm.ChatCompletionRequest
import advent.day3.llm.ChatCompletionResponse
import advent.day3.llm.Choice
import advent.day3.llm.LlmClient
import advent.day3.llm.LlmException
import advent.day3.llm.LlmExchange
import advent.day3.llm.Usage
import org.junit.jupiter.api.BeforeEach
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
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Стаб провайдера, отвечающий по-разному в зависимости от полученной инструкции.
 * Прямой ответ намеренно ошибается — ради этого расхождения задание и делается.
 */
class ScriptedLlmClient : LlmClient {
    val requests = CopyOnWriteArrayList<ChatCompletionRequest>()

    /** По какому фрагменту системной инструкции способ должен упасть. null — никто не падает. */
    @Volatile var failOn: String? = null

    @Volatile var failEverything = false

    fun reset() {
        requests.clear()
        failOn = null
        failEverything = false
    }

    override fun complete(request: ChatCompletionRequest): LlmExchange {
        requests += request

        val system = request.messages.firstOrNull { it.role == "system" }?.content.orEmpty()
        val user = request.messages.last { it.role == "user" }.content.orEmpty()

        if (failEverything) throw LlmException("LLM отклонил ключ (401). Проверьте DEEPSEEK_API_KEY.")
        failOn?.takeIf { system.contains(it) }?.let { throw LlmException("LLM временно недоступен (503).") }

        val content = when {
            system.contains("инженер по промптам") -> GENERATED_PROMPT
            system.contains("трёх экспертов") -> EXPERT_ANSWER
            system.contains("пошагово") -> STEP_ANSWER
            user == GENERATED_PROMPT -> META_ANSWER
            else -> DIRECT_ANSWER
        }

        return LlmExchange(
            url = "https://api.deepseek.com/chat/completions",
            method = "POST",
            requestHeaders = mapOf("Authorization" to "Bearer sk-tes…4242"),
            requestBody = """{"model":"${request.model}"}""",
            status = 200,
            responseBody = """{"choices":[{"message":{"content":"…"}}]}""",
            parsed = ChatCompletionResponse(
                id = "test",
                model = request.model,
                choices = listOf(
                    Choice(message = ApiMessage(role = "assistant", content = content), finishReason = "stop"),
                ),
                usage = Usage(promptTokens = 10, completionTokens = 20, totalTokens = 30),
            ),
        )
    }

    companion object {
        const val GENERATED_PROMPT =
            "Ты — преподаватель геометрии. Посчитай, сколько раз в сутки стрелки образуют прямой угол. ОТВЕТ: <число>"
        const val DIRECT_ANSWER = "Каждый час дважды, значит 48.\nОТВЕТ: 48"
        const val STEP_ANSWER = "Шаг 1. За 12 часов 22 совпадения.\nШаг 2. Удваиваем.\nОТВЕТ: 44"
        const val META_ANSWER = "По этому промпту получается 44.\nОТВЕТ: 44"
        val EXPERT_ANSWER = """
### Аналитик
Спрашивают про сутки.

### Инженер
За 12 часов 22 прямых угла.

### Критик
Ловушка — считать по два на час.

### Итог
ОТВЕТ: 44
""".trim()
    }
}

@SpringBootTest
@AutoConfigureMockMvc
class SolveApiTest {

    @TestConfiguration
    class Stubs {
        @Bean
        @Primary
        fun scriptedLlmClient() = ScriptedLlmClient()
    }

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var llmClient: ScriptedLlmClient

    @BeforeEach
    fun setUp() = llmClient.reset()

    private fun solve(body: String) = mockMvc.perform(
        post("/api/solve").contentType(MediaType.APPLICATION_JSON).content(body),
    )

    @Test
    fun `четыре способа отрабатывают и попадают в сводку в порядке справочника`() {
        solve("""{"task":"Сколько раз в сутки стрелки образуют прямой угол?"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.runs.length()").value(4))
            .andExpect(jsonPath("$.runs[0].technique").value("DIRECT"))
            .andExpect(jsonPath("$.runs[1].technique").value("STEP_BY_STEP"))
            .andExpect(jsonPath("$.runs[2].technique").value("META_PROMPT"))
            .andExpect(jsonPath("$.runs[3].technique").value("EXPERT_PANEL"))
            .andExpect(jsonPath("$.answerMarker").value("ОТВЕТ:"))
            .andExpect(jsonPath("$.runs[0].finalAnswer").value("48"))
            .andExpect(jsonPath("$.runs[1].finalAnswer").value("44"))

        // Мета-промпт ходит дважды, остальные по разу.
        assertEquals(5, llmClient.requests.size)
    }

    @Test
    fun `мета-промпт решает задачу по промпту, который сам же и сочинил`() {
        solve("""{"task":"Задача","techniques":["META_PROMPT"]}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.runs[0].calls").value(2))
            .andExpect(jsonPath("$.runs[0].steps.length()").value(2))
            .andExpect(jsonPath("$.runs[0].steps[1].userPrompt").value(ScriptedLlmClient.GENERATED_PROMPT))
            .andExpect(jsonPath("$.runs[0].finalAnswer").value("44"))

        val second = llmClient.requests.last()
        assertEquals(ScriptedLlmClient.GENERATED_PROMPT, second.messages.last { it.role == "user" }.content)
    }

    @Test
    fun `расхождение финальных ответов видно в сводке`() {
        solve("""{"task":"Задача"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.comparison.allAgree").value(false))
            .andExpect(jsonPath("$.comparison.groups.length()").value(2))
            .andExpect(jsonPath("$.comparison.groups[0].answer").value("44"))
            .andExpect(jsonPath("$.comparison.groups[0].count").value(3))
            .andExpect(jsonPath("$.comparison.groups[1].answer").value("48"))
    }

    @Test
    fun `ответ группы экспертов раскладывается по ролям`() {
        solve("""{"task":"Задача","techniques":["EXPERT_PANEL"]}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.runs[0].sections.length()").value(4))
            .andExpect(jsonPath("$.runs[0].sections[0].title").value("Аналитик"))
            .andExpect(jsonPath("$.runs[0].sections[3].title").value("Итог"))
    }

    @Test
    fun `без общей добавки прямой ответ уходит вообще без системной инструкции`() {
        solve("""{"task":"Задача","techniques":["DIRECT"],"requireAnswerLine":false}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.answerMarker").doesNotExist())
            .andExpect(jsonPath("$.runs[0].steps[0].systemPrompt").doesNotExist())

        assertTrue(llmClient.requests.single().messages.none { it.role == "system" })
    }

    @Test
    fun `с общей добавкой прямой ответ получает только требование финальной строки`() {
        solve("""{"task":"Задача","techniques":["DIRECT"]}""").andExpect(status().isOk)

        val system = llmClient.requests.single().messages.single { it.role == "system" }.content!!
        assertTrue(system.contains("ОТВЕТ:"))
        assertTrue(system.lines().size == 1, "у прямого ответа не должно быть ничего, кроме одной строки")
    }

    @Test
    fun `температура и модель одни на все способы`() {
        solve("""{"task":"Задача","model":"deepseek-chat","params":{"temperature":0}}""")
            .andExpect(status().isOk)

        assertTrue(llmClient.requests.all { it.temperature == 0.0 && it.model == "deepseek-chat" })
    }

    @Test
    fun `упавший способ не роняет остальные`() {
        llmClient.failOn = "пошагово"

        solve("""{"task":"Задача"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.runs[1].failure").isNotEmpty)
            .andExpect(jsonPath("$.runs[1].answer").doesNotExist())
            .andExpect(jsonPath("$.runs[0].answer").isNotEmpty)
            .andExpect(jsonPath("$.runs[3].answer").isNotEmpty)
            // Упавший способ в сравнении не участвует: 48 против 44 у двух оставшихся.
            .andExpect(jsonPath("$.comparison.groups.length()").value(2))
    }

    @Test
    fun `когда причина отказа общая — она и отдаётся, а не четыре одинаковые карточки`() {
        llmClient.failEverything = true

        solve("""{"task":"Задача"}""")
            .andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.error").value("LLM отклонил ключ (401). Проверьте DEEPSEEK_API_KEY."))
    }

    @Test
    fun `пустая задача и пустой список способов отклоняются до похода в сеть`() {
        solve("""{"task":"  "}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.details[0]").value("task: Введите задачу"))

        solve("""{"task":"Задача","techniques":[]}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.details[0]").value("techniques: Выберите хотя бы один способ"))

        assertTrue(llmClient.requests.isEmpty())
    }

    @Test
    fun `неизвестная модель не уходит во внешний API`() {
        solve("""{"task":"Задача","techniques":["DIRECT"],"model":"gpt-4"}""")
            .andExpect(status().isBadRequest)

        assertTrue(llmClient.requests.isEmpty())
    }

    @Test
    fun `справочник способов отдаётся фронту вместе с числом запросов`() {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/techniques"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(4))
            .andExpect(jsonPath("$[2].id").value("META_PROMPT"))
            .andExpect(jsonPath("$[2].calls").value(2))
            .andExpect(jsonPath("$[0].title").value("Прямой ответ"))
    }

    @Test
    fun `сырой HTTP-обмен возвращается по каждому вызову`() {
        solve("""{"task":"Задача","techniques":["META_PROMPT"]}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.runs[0].steps[0].exchange.status").value(200))
            .andExpect(jsonPath("$.runs[0].steps[1].exchange.status").value(200))
            .andExpect(jsonPath("$.runs[0].steps[0].exchange.requestHeaders.Authorization").value("Bearer sk-tes…4242"))

        assertNull(llmClient.requests.first().maxTokens, "потолок длины не навязывается способом")
    }
}

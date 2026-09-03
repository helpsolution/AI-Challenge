package advent.day4

import advent.day4.llm.ApiMessage
import advent.day4.llm.ChatCompletionRequest
import advent.day4.llm.ChatCompletionResponse
import advent.day4.llm.Choice
import advent.day4.llm.LlmClient
import advent.day4.llm.LlmException
import advent.day4.llm.LlmExchange
import advent.day4.llm.Usage
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Стаб провайдера, который ведёт себя как модель: на нуле отвечает всегда одинаково,
 * на горячих температурах — каждый раз иначе. Ради этой разницы задание и делается.
 */
class ScriptedLlmClient : LlmClient {
    val requests = CopyOnWriteArrayList<ChatCompletionRequest>()
    private val counter = AtomicInteger()

    /** Температура, на которой провайдер отказывает. null — не отказывает. */
    @Volatile var failOnTemperature: Double? = null

    @Volatile var failEverything = false

    fun reset() {
        requests.clear()
        counter.set(0)
        failOnTemperature = null
        failEverything = false
    }

    override fun complete(request: ChatCompletionRequest): LlmExchange {
        requests += request

        if (failEverything) throw LlmException("LLM отклонил ключ (401). Проверьте DEEPSEEK_API_KEY.")
        failOnTemperature?.takeIf { it == request.temperature }?.let {
            throw LlmException("LLM временно недоступен (503).")
        }

        val n = counter.incrementAndGet()
        val content = when {
            (request.temperature ?: 0.0) <= 0.3 -> COLD
            (request.temperature ?: 0.0) <= 0.9 -> warm(n)
            else -> hot(n)
        }

        return LlmExchange(
            url = "https://api.deepseek.com/chat/completions",
            method = "POST",
            requestHeaders = mapOf("Authorization" to "Bearer sk-tes…4242"),
            requestBody = """{"model":"${request.model}","temperature":${request.temperature}}""",
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
        /** На нуле ответ один и тот же от прогона к прогону. */
        const val COLD = "Считаем по шагам: 2400 стало 2760, затем 2208.\nОТВЕТ: 2208 рублей"

        fun warm(n: Int) = "Сначала цена выросла, потом её уценили — вышло 2208 ($n).\nОТВЕТ: 2208"

        fun hot(n: Int) = "Вихрь номер $n: цифры пляшут, монета падает на 2200.\nОТВЕТ: 2200"
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

    @BeforeEach
    fun setUp() = llmClient.reset()

    private fun compare(body: String) = mockMvc.perform(
        post("/api/compare").contentType(MediaType.APPLICATION_JSON).content(body),
    )

    @Test
    fun `три температуры уходят в API ровно как заданы, по одному запросу на каждую`() {
        compare("""{"prompt":"Задача"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.lanes.length()").value(3))
            .andExpect(jsonPath("$.lanes[0].temperature").value(0.0))
            .andExpect(jsonPath("$.lanes[1].temperature").value(0.7))
            .andExpect(jsonPath("$.lanes[2].temperature").value(1.2))
            .andExpect(jsonPath("$.lanes[0].band").value("COLD"))
            .andExpect(jsonPath("$.lanes[1].band").value("WARM"))
            .andExpect(jsonPath("$.lanes[2].band").value("HOT"))

        assertEquals(listOf(0.0, 0.7, 1.2), llmClient.requests.mapNotNull { it.temperature }.sorted())
    }

    @Test
    fun `кроме температуры в запросах не меняется ничего`() {
        compare("""{"prompt":"Задача","model":"deepseek-chat"}""").andExpect(status().isOk)

        val prompts = llmClient.requests.map { r -> r.messages.map { it.role to it.content } }.distinct()
        assertEquals(1, prompts.size, "запрос должен быть у всех прогонов одинаковым")
        assertTrue(llmClient.requests.all { it.model == "deepseek-chat" && it.maxTokens == null })
    }

    @Test
    fun `полосы отдаются по возрастанию, дубли схлопываются`() {
        compare("""{"prompt":"Задача","temperatures":[1.2,0.0,1.2]}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.lanes.length()").value(2))
            .andExpect(jsonPath("$.lanes[0].temperature").value(0.0))
            .andExpect(jsonPath("$.lanes[1].temperature").value(1.2))

        assertEquals(2, llmClient.requests.size, "повтор той же температуры не должен стоить лишнего запроса")
    }

    @Test
    fun `при одном прогоне разброс внутри температуры не измеряется`() {
        compare("""{"prompt":"Задача","temperatures":[0.0],"runs":1}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.runs").value(1))
            .andExpect(jsonPath("$.lanes[0].selfSpread").doesNotExist())
            .andExpect(jsonPath("$.lanes[0].distinctAnswers").doesNotExist())
    }

    @Test
    fun `при нескольких прогонах видно, повторяется ответ или каждый раз новый`() {
        compare("""{"prompt":"Задача","temperatures":[0.0,1.2],"runs":3}""")
            .andExpect(status().isOk)
            // На нуле стаб отвечает одинаково — один различный ответ и нулевой разброс.
            .andExpect(jsonPath("$.lanes[0].distinctAnswers").value(1))
            .andExpect(jsonPath("$.lanes[0].selfSpread").value(0.0))
            .andExpect(jsonPath("$.lanes[1].distinctAnswers").value(3))
            .andExpect(jsonPath("$.lanes[1].runs.length()").value(3))
            .andExpect(jsonPath("$.lanes[1].runs[2].index").value(3))

        assertEquals(6, llmClient.requests.size)
    }

    @Test
    fun `разброс на горячей температуре больше, чем на нулевой`() {
        val body = mockMvc.perform(
            post("/api/compare").contentType(MediaType.APPLICATION_JSON)
                .content("""{"prompt":"Задача","temperatures":[0.0,1.2],"runs":2}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString

        val cold = Regex(""""selfSpread":([0-9.]+)""").findAll(body).map { it.groupValues[1].toDouble() }.toList()
        assertEquals(2, cold.size)
        assertTrue(cold[0] < cold[1], "на нуле разброс должен быть меньше: $cold")
    }

    @Test
    fun `расстояния считаются по всем парам температур`() {
        compare("""{"prompt":"Задача"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.comparison.distances.length()").value(3))
            .andExpect(jsonPath("$.comparison.distances[0].from").value(0.0))
            .andExpect(jsonPath("$.comparison.distances[0].to").value(0.7))
            .andExpect(jsonPath("$.comparison.distances[2].from").value(0.7))
            .andExpect(jsonPath("$.comparison.distances[2].to").value(1.2))
    }

    @Test
    fun `две температуры с одинаковыми ответами дают нулевое расстояние`() {
        compare("""{"prompt":"Задача","temperatures":[0.0,0.2]}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.comparison.distances[0].distance").value(0.0))
    }

    @Test
    fun `без маркера запрос уходит голым и финальную строку не выделяем`() {
        compare("""{"prompt":"Задача","temperatures":[0.0]}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.systemPrompt").doesNotExist())
            .andExpect(jsonPath("$.answerMarker").doesNotExist())
            .andExpect(jsonPath("$.lanes[0].runs[0].finalAnswer").doesNotExist())
            .andExpect(jsonPath("$.comparison.answerGroups.length()").value(0))

        assertTrue(llmClient.requests.single().messages.none { it.role == "system" })
    }

    @Test
    fun `маркер добавляет одну инструкцию, общую для всех температур`() {
        compare("""{"prompt":"Задача","requireAnswerLine":true}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.answerMarker").value("ОТВЕТ:"))
            .andExpect(jsonPath("$.systemPrompt").isNotEmpty)

        val systems = llmClient.requests.map { r -> r.messages.single { it.role == "system" }.content }.distinct()
        assertEquals(1, systems.size, "инструкция должна быть у всех прогонов одна и та же")
        assertTrue(systems.single()!!.contains("ОТВЕТ:"))
    }

    @Test
    fun `финальные строки группируются, и видно, на какой температуре ответ поплыл`() {
        compare("""{"prompt":"Задача","requireAnswerLine":true}""")
            .andExpect(status().isOk)
            // «2208 рублей» и «2208» — один ответ: единица счёта снимается при сравнении.
            .andExpect(jsonPath("$.comparison.answerGroups.length()").value(2))
            .andExpect(jsonPath("$.comparison.answerGroups[0].count").value(2))
            .andExpect(jsonPath("$.comparison.answerGroups[0].temperatures[0]").value(0.0))
            .andExpect(jsonPath("$.comparison.answerGroups[0].temperatures[1]").value(0.7))
            .andExpect(jsonPath("$.comparison.answerGroups[1].answer").value("2200"))
            .andExpect(jsonPath("$.comparison.answerGroups[1].temperatures[0]").value(1.2))
    }

    @Test
    fun `метрики текста приходят по каждому прогону и усреднённые по полосе`() {
        compare("""{"prompt":"Задача","temperatures":[0.0]}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.lanes[0].runs[0].metrics.words").value(11))
            .andExpect(jsonPath("$.lanes[0].metrics.words").value(11))
            .andExpect(jsonPath("$.lanes[0].metrics.lexicalVariety").value(0.91))
            .andExpect(jsonPath("$.lanes[0].completionTokens").value(20))
    }

    @Test
    fun `упавшая температура не роняет остальные`() {
        llmClient.failOnTemperature = 0.7

        compare("""{"prompt":"Задача"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.lanes[1].failures").value(1))
            .andExpect(jsonPath("$.lanes[1].runs[0].failure").isNotEmpty)
            .andExpect(jsonPath("$.lanes[1].metrics").doesNotExist())
            .andExpect(jsonPath("$.lanes[0].runs[0].answer").isNotEmpty)
            .andExpect(jsonPath("$.lanes[2].runs[0].answer").isNotEmpty)
            // Упавшая полоса в сравнении не участвует: остаётся одна пара из двух живых.
            .andExpect(jsonPath("$.comparison.distances.length()").value(1))
    }

    @Test
    fun `когда причина отказа общая — она и отдаётся, а не три одинаковые плашки`() {
        llmClient.failEverything = true

        compare("""{"prompt":"Задача"}""")
            .andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.error").value("LLM отклонил ключ (401). Проверьте DEEPSEEK_API_KEY."))
    }

    @Test
    fun `заведомо невалидные параметры отсекаются до похода в сеть`() {
        compare("""{"prompt":"  "}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.details[0]").value("prompt: Введите запрос"))

        compare("""{"prompt":"Задача","temperatures":[]}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.details[0]").value("temperatures: Выберите хотя бы одну температуру"))

        compare("""{"prompt":"Задача","runs":6}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.details[0]").value("runs: максимум 5 прогонов"))

        compare("""{"prompt":"Задача","temperatures":[2.5]}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("temperature: значение 2.5 вне диапазона 0.0–2.0"))

        compare("""{"prompt":"Задача","temperatures":[0.0,0.5,1.0,1.5,2.0]}""")
            .andExpect(status().isBadRequest)

        assertTrue(llmClient.requests.isEmpty())
    }

    @Test
    fun `неизвестная модель не уходит во внешний API`() {
        compare("""{"prompt":"Задача","temperatures":[0.0],"model":"gpt-4"}""")
            .andExpect(status().isBadRequest)

        assertTrue(llmClient.requests.isEmpty())
    }

    @Test
    fun `сырой HTTP-обмен возвращается по каждому прогону`() {
        compare("""{"prompt":"Задача","temperatures":[1.2]}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.lanes[0].runs[0].exchange.status").value(200))
            .andExpect(jsonPath("$.lanes[0].runs[0].exchange.requestHeaders.Authorization").value("Bearer sk-tes…4242"))
            .andExpect(jsonPath("$.lanes[0].runs[0].exchange.requestBody").value(org.hamcrest.Matchers.containsString("\"temperature\":1.2")))
    }

    @Test
    fun `справочник диапазонов отдаётся фронту с границами`() {
        mockMvc.perform(get("/api/bands"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(4))
            .andExpect(jsonPath("$[0].id").value("COLD"))
            .andExpect(jsonPath("$[0].upperBound").value(0.3))
            .andExpect(jsonPath("$[2].id").value("HOT"))
            .andExpect(jsonPath("$[2].bestFor.length()").value(4))
            .andExpect(jsonPath("$[3].id").value("SCALDING"))
    }

    @Test
    fun `в белом списке моделей только та, что реагирует на температуру`() {
        mockMvc.perform(get("/api/models"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.models.length()").value(1))
            .andExpect(jsonPath("$.models[0]").value("deepseek-chat"))
            .andExpect(jsonPath("$.defaultModel").value("deepseek-chat"))
    }
}

package advent.day5

import org.hamcrest.Description
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.greaterThan
import org.hamcrest.TypeSafeMatcher
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Прогон целиком: три модели разного класса на один запрос, замеры и сведение.
 *
 * Провайдер заменён заглушкой, прайс-лист — тоже: тест проверяет арифметику и порядок
 * вызовов, а не то, что интернет на месте.
 */
@SpringBootTest(properties = ["spring.main.allow-bean-definition-overriding=true"])
@AutoConfigureMockMvc
class CompareApiTest {

    @TestConfiguration
    class Stubs {
        @Bean
        @Primary
        fun scriptedLlmClient() = ScriptedLlmClient()

        /** Прайс-лист провайдера. Отдаётся из памяти, чтобы тест не ходил в сеть. */
        @Bean
        fun catalogRestClient(): RestClient {
            val builder = RestClient.builder()
            MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build()
                .expect(ExpectedCount.manyTimes(), requestTo("https://openrouter.ai/api/v1/models"))
                .andRespond(withSuccess(PRICES, MediaType.APPLICATION_JSON))
            return builder.build()
        }
    }

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var llmClient: ScriptedLlmClient

    @BeforeEach
    fun setUp() = llmClient.reset()

    private fun compare(body: String) = mockMvc.perform(
        post("/api/compare").contentType(MediaType.APPLICATION_JSON).content(body),
    )

    private fun models() = llmClient.requests.map { it.model }

    @Test
    fun `один запрос уходит на три уровня, и уровни идут от слабого к сильному`() {
        compare("""{"prompt":"Бита и мяч"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.contenders.length()").value(3))
            .andExpect(jsonPath("$.contenders[0].tier").value("WEAK"))
            .andExpect(jsonPath("$.contenders[1].tier").value("MEDIUM"))
            .andExpect(jsonPath("$.contenders[2].tier").value("STRONG"))
            .andExpect(jsonPath("$.contenders[0].model.id").value("meta-llama/llama-3.2-1b-instruct"))
            .andExpect(jsonPath("$.contenders[1].model.id").value("deepseek/deepseek-chat"))
            .andExpect(jsonPath("$.contenders[2].model.id").value("anthropic/claude-sonnet-5"))

        assertEquals(3, llmClient.requests.size, "по одному запросу на уровень")
    }

    /**
     * Настройки генерации не приходят с фронта: их там больше нет. Задача этого дня —
     * сравнить уровни моделей, а подкрученная одному участнику температура такое
     * сравнение сломала бы.
     */
    @Test
    fun `кроме самой модели в запросах не меняется ничего`() {
        compare("""{"prompt":"Бита и мяч","temperature":0.9,"maxTokens":64}""").andExpect(status().isOk)

        val sent = llmClient.requests
        assertEquals(3, sent.size)
        assertEquals(1, sent.map { r -> r.messages.map { it.role to it.content } }.distinct().size)
        assertTrue(sent.all { it.temperature == 0.2 && it.maxTokens == 2_000 }, "настройки заданы приложением")
        assertTrue(sent.all { it.messages.none { m -> m.role == "system" } }, "запрос должен уходить голым")
    }

    @Test
    fun `модель можно выбрать вручную внутри своего уровня`() {
        compare("""{"prompt":"Бита и мяч","models":{"MEDIUM":"deepseek/deepseek-chat"}}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.contenders[1].model.title").value("DeepSeek V3"))
    }

    @Test
    fun `модель не из каталога во внешний API не уходит`() {
        compare("""{"prompt":"Бита и мяч","models":{"WEAK":"openai/gpt-4"}}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("Модель 'openai/gpt-4' не входит в каталог"))

        assertTrue(llmClient.requests.isEmpty())
    }

    @Test
    fun `сильную модель нельзя выдать за слабую — уровень проверяется`() {
        compare("""{"prompt":"Бита и мяч","models":{"WEAK":"anthropic/claude-sonnet-5"}}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value(containsString("относится к уровню «Сильная»")))

        assertTrue(llmClient.requests.isEmpty())
    }

    @Test
    fun `стоимость берётся из ответа провайдера, а не считается по прайсу`() {
        compare("""{"prompt":"Бита и мяч"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.contenders[0].cost.amount").value(0.0000102))
            .andExpect(jsonPath("$.contenders[0].cost.estimated").value(false))
            // Доли цента человеку ни о чём не говорят, тысяча запросов — говорит.
            .andExpect(jsonPath("$.contenders[0].cost.per1000Requests").value(near(0.0102)))
            .andExpect(jsonPath("$.contenders[2].cost.amount").value(0.00326))
            .andExpect(jsonPath("$.costEstimated").value(false))
    }

    @Test
    fun `если провайдер не прислал сумму, она считается по прайсу и помечается оценкой`() {
        llmClient.omitCost = true

        compare("""{"prompt":"Бита и мяч"}""")
            .andExpect(status().isOk)
            // 30 входных по $0.027/M и 40 выходных по $0.201/M.
            .andExpect(jsonPath("$.contenders[0].cost.amount").value(near(30 * 0.027e-6 + 40 * 0.201e-6)))
            .andExpect(jsonPath("$.contenders[0].cost.estimated").value(true))
            .andExpect(jsonPath("$.costEstimated").value(true))
    }

    @Test
    fun `токены и скрытое рассуждение приходят по каждому участнику`() {
        compare("""{"prompt":"Бита и мяч"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.contenders[0].usage.completionTokens").value(40))
            .andExpect(jsonPath("$.contenders[0].usage.reasoningTokens").value(0))
            .andExpect(jsonPath("$.contenders[2].usage.completionTokens").value(320))
            .andExpect(jsonPath("$.contenders[2].usage.reasoningTokens").value(120))
            .andExpect(jsonPath("$.contenders[2].provider").value("Anthropic"))
    }

    @Test
    fun `кратности считаются от самого быстрого и самого дешёвого участника`() {
        compare("""{"prompt":"Бита и мяч"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.contenders[0].latencyRatio").value(1.0))
            .andExpect(jsonPath("$.contenders[0].costRatio").value(1.0))
            .andExpect(jsonPath("$.contenders[2].latencyRatio").value(greaterThan(1.0)))
            // 0.00326 / 0.0000102 ≈ 319.6
            .andExpect(jsonPath("$.contenders[2].costRatio").value(near(319.61, tolerance = 0.01)))
    }

    @Test
    fun `скорость меряется в токенах в секунду, а не длиной ответа`() {
        compare("""{"prompt":"Бита и мяч"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.contenders[0].tokensPerSecond").value(greaterThan(0.0)))
            .andExpect(jsonPath("$.contenders[2].tokensPerSecond").value(greaterThan(0.0)))
    }

    @Test
    fun `параллельный опрос быстрее суммы задержек`() {
        val body = compare("""{"prompt":"Бита и мяч"}""")
            .andExpect(status().isOk).andReturn().response.contentAsString

        val wallClock = Regex(""""wallClockMs":(\d+)""").find(body)!!.groupValues[1].toLong()
        val latencies = Regex(""""latencyMs":(\d+)""").findAll(body).map { it.groupValues[1].toLong() }.toList()

        assertEquals(3, latencies.size)
        assertTrue(wallClock < latencies.sum(), "иначе замер мерил бы очередь, а не модели: $wallClock vs $latencies")
    }

    @Test
    fun `стоимость прогона — сумма по участникам`() {
        compare("""{"prompt":"Бита и мяч"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalCost").value(near(0.0000102 + 0.000175 + 0.00326)))
    }

    /** Качество приложение не оценивает: объективной меры нет, а выдумывать её оно не берётся. */
    @Test
    fun `оценки качества и сводного вывода в ответе нет`() {
        compare("""{"prompt":"Бита и мяч"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.judge").doesNotExist())
            .andExpect(jsonPath("$.conclusion").doesNotExist())
            .andExpect(jsonPath("$.contenders[0].score").doesNotExist())
            .andExpect(jsonPath("$.contenders[0].judgeComment").doesNotExist())
            .andExpect(jsonPath("$.contenders[0].costPerScore").doesNotExist())
    }

    @Test
    fun `упавший участник не роняет остальных`() {
        llmClient.failOn = "deepseek/deepseek-chat"

        compare("""{"prompt":"Бита и мяч"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.contenders[1].failure").value(containsString("502")))
            .andExpect(jsonPath("$.contenders[1].answer").doesNotExist())
            .andExpect(jsonPath("$.contenders[0].answer").isNotEmpty)
            .andExpect(jsonPath("$.contenders[2].answer").isNotEmpty)
            // Упавший в сравнении не участвует, но кратности живых считаются как обычно.
            .andExpect(jsonPath("$.contenders[2].costRatio").value(near(319.61, tolerance = 0.01)))
    }

    @Test
    fun `когда причина отказа общая — она и отдаётся, а не три одинаковые плашки`() {
        llmClient.failEverything = true

        compare("""{"prompt":"Бита и мяч"}""")
            .andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.error").value("Недостаточно средств на балансе OpenRouter (402). Пополните счёт."))
    }

    @Test
    fun `заведомо невалидные параметры отсекаются до похода в сеть`() {
        compare("""{"prompt":"  "}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.details[0]").value("prompt: Введите запрос"))

        assertTrue(llmClient.requests.isEmpty())
    }

    @Test
    fun `настройки генерации, общие для всех, видны в ответе`() {
        compare("""{"prompt":"Бита и мяч"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.temperature").value(0.2))
            .andExpect(jsonPath("$.maxTokens").value(2000))
    }

    @Test
    fun `сырой HTTP-обмен возвращается по каждому участнику`() {
        compare("""{"prompt":"Бита и мяч"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.contenders[0].exchange.status").value(200))
            .andExpect(jsonPath("$.contenders[0].exchange.requestHeaders.Authorization").value("Bearer sk-or-v1-…4242"))
    }

    @Test
    fun `каталог отдаёт уровни, цены и ссылки на модели`() {
        mockMvc.perform(get("/api/catalog"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tiers.length()").value(3))
            .andExpect(jsonPath("$.tiers[0].id").value("WEAK"))
            .andExpect(jsonPath("$.tiers[2].goodFor.length()").value(3))
            .andExpect(jsonPath("$.pricingUnavailable").value(false))
            .andExpect(jsonPath("$.models[0].id").value("meta-llama/llama-3.2-1b-instruct"))
            .andExpect(jsonPath("$.models[0].default").value(true))
            .andExpect(jsonPath("$.models[0].promptPricePerMillion").value(near(0.027)))
            .andExpect(jsonPath("$.models[0].completionPricePerMillion").value(near(0.201)))
            .andExpect(jsonPath("$.models[0].contextLength").value(60000))
            .andExpect(jsonPath("$.models[0].openRouterUrl")
                .value("https://openrouter.ai/meta-llama/llama-3.2-1b-instruct"))
            .andExpect(jsonPath("$.models[0].huggingFaceUrl")
                .value("https://huggingface.co/meta-llama/Llama-3.2-1B-Instruct"))
    }

    @Test
    fun `у закрытой модели ссылки на веса нет, и это не ошибка`() {
        mockMvc.perform(get("/api/catalog"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.models[?(@.id=='anthropic/claude-sonnet-5')].title")
                .value(org.hamcrest.Matchers.contains("Claude Sonnet 5")))
            .andExpect(jsonPath("$.models[?(@.id=='anthropic/claude-sonnet-5')].huggingFaceUrl")
                .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())))
    }

    @Test
    fun `заготовленные задачи отдаются фронту`() {
        mockMvc.perform(get("/api/presets"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(5))
            .andExpect(jsonPath("$[0].id").value("logic"))
            .andExpect(jsonPath("$[0].prompt").value(containsString("1100")))
            .andExpect(jsonPath("$[0].hint").isNotEmpty)
    }

    /**
     * JSON-число доезжает сюда то как Double, то как BigDecimal — зависит от того,
     * сколько у него значащих цифр. Сравниваем по значению, а не по типу.
     */
    private fun near(expected: Double, tolerance: Double = 1e-12) = object : TypeSafeMatcher<Number>() {
        override fun matchesSafely(item: Number) = Math.abs(item.toDouble() - expected) <= tolerance
        override fun describeTo(description: Description) {
            description.appendText("число около ").appendValue(expected)
        }
    }

    private companion object {
        /** Урезанный ответ /api/v1/models: только те поля, которые каталог действительно читает. */
        val PRICES = """
            {"data":[
              {"id":"meta-llama/llama-3.2-1b-instruct","context_length":60000,
               "hugging_face_id":"meta-llama/Llama-3.2-1B-Instruct",
               "pricing":{"prompt":"0.000000027","completion":"0.000000201"}},
              {"id":"deepseek/deepseek-chat","context_length":163840,
               "hugging_face_id":"deepseek-ai/DeepSeek-V3",
               "pricing":{"prompt":"0.00000032","completion":"0.00000089"}},
              {"id":"anthropic/claude-sonnet-5","context_length":1000000,
               "hugging_face_id":null,
               "pricing":{"prompt":"0.000002","completion":"0.00001"}}
            ]}
        """.trimIndent()
    }
}

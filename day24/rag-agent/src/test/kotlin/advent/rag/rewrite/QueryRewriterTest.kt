package advent.rag.rewrite

import advent.rag.agent.PromptBuilder
import advent.rag.llm.ChatMessage
import advent.rag.llm.LlmClient
import advent.rag.llm.LlmProperties
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.net.InetSocketAddress
import java.time.Duration

class QueryRewriterTest {
    private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val history = listOf(ChatMessage("user", "Что такое ADR?"), ChatMessage("assistant", "ADR фиксирует решение [1]."))

    @Test fun `resolves follow up using history and leaves answer prompt question unchanged`() {
        withModel("""{"query":"Когда не нужно создавать ADR?","reason":"Раскрыто местоимение"}""") { rewriter, requests ->
            val result = rewriter.rewrite("А когда он не нужен?", history).decision
            assertEquals("rewritten", result.status)
            assertEquals("Когда не нужно создавать ADR?", result.query)
            assertEquals("А когда он не нужен?", result.input)
            assertEquals("test-model", result.model)
            val request = json.readTree(requests.single())
            assertEquals("Что такое ADR?", request["messages"][1]["content"].asString())
            assertEquals(384, request["max_tokens"].asInt())
            assertEquals(0.0, request["temperature"].asDouble())
            assertEquals("json_object", request["response_format"]["type"].asString())
            assertTrue(PromptBuilder().answer(result.input, history, emptyList()).last().content.endsWith("Текущий вопрос: А когда он не нужен?"))
        }
    }

    @Test fun `disabled rewriting makes no llm request and keeps legacy follow up context`() {
        withModel("unused", enabled = false) { rewriter, requests ->
            val result = rewriter.rewrite("А когда он не нужен?", history).decision
            assertEquals("disabled", result.status)
            assertEquals("Предыдущий вопрос: Что такое ADR?\nУточнение: А когда он не нужен?", result.query)
            assertFalse(result.enabled)
            assertTrue(requests.isEmpty())
        }
    }

    @Test fun `recognizes unchanged standalone query`() {
        withModel("""{"query":"Что такое ADR?","reason":"Вопрос самодостаточен"}""") { rewriter, _ ->
            val result = rewriter.rewrite("Что такое ADR?", emptyList()).decision
            assertEquals("unchanged", result.status)
            assertFalse(result.changed)
        }
    }

    @Test fun `accepts equivalent formatting of decimal and grouped numbers`() {
        withModel("""{"query":"Рассчитать concurrency при 15000 RPS и задержке 50 мс для SLA 99.9%","reason":"Краткая формулировка"}""") { rewriter, _ ->
            val result = rewriter.rewrite("При 15 000 RPS, 50 мс и SLA 99,9% сколько запросов в работе?", emptyList()).decision
            assertEquals("rewritten", result.status)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "not-json",
        """{"query":"","reason":"Пусто"}""",
        """{"query":4,"reason":"Неверный тип"}""",
        """{"query":"Объясни p95","reason":"Потеря числа"}""",
        """{"query":"Объясни 99.99% и p95","reason":"Замена числа"}""",
        """{"query":"Объясни 99.9% и p99","reason":"Замена перцентиля"}""",
    ])
    fun `rejects malformed or changed constraints and exposes fallback`(content: String) {
        withModel(content) { rewriter, _ ->
            val result = rewriter.rewrite("Объясни 99.9% и p95", emptyList())
            assertEquals("fallback", result.decision.status)
            assertEquals("Объясни 99.9% и p95", result.decision.query)
            assertNotNull(result.completion)
        }
    }

    @Test fun `rejects truncated completion and preserves follow up context`() {
        withModel("""{"query":"Когда не нужен ADR?","reason":"Уточнение"}""", finishReason = "length") { rewriter, _ ->
            val result = rewriter.rewrite("А когда он не нужен?", history).decision
            assertEquals("fallback", result.status)
            assertEquals(result.baselineQuery, result.query)
        }
    }

    @Test fun `llm failure falls back without stopping retrieval`() {
        withModel("unavailable", status = 503) { rewriter, _ ->
            val result = rewriter.rewrite("А когда он не нужен?", history)
            assertEquals("fallback", result.decision.status)
            assertEquals(result.decision.baselineQuery, result.decision.query)
            assertNull(result.completion)
        }
    }

    private fun withModel(content: String, status: Int = 200, finishReason: String = "stop", enabled: Boolean = true,
                          test: (QueryRewriter, List<String>) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<String>()
        server.createContext("/chat/completions") { exchange ->
            exchange.use {
                requests += it.requestBody.bufferedReader().readText()
                val body = if (status == 200) json.writeValueAsString(mapOf("model" to "test-model", "choices" to listOf(
                    mapOf("message" to mapOf("content" to content), "finish_reason" to finishReason)), "usage" to null)) else content
                val bytes = body.toByteArray()
                it.responseHeaders.add("Content-Type", "application/json")
                it.sendResponseHeaders(status, bytes.size.toLong())
                it.responseBody.write(bytes)
            }
        }
        server.start()
        val llm = LlmClient(RestClient.builder(), json, LlmProperties("http://127.0.0.1:${server.address.port}",
            "test-key", "test-model", 0.2, Duration.ofSeconds(5)))
        try { test(QueryRewriter(llm, PromptBuilder(), json, QueryRewriteProperties(enabled)), requests) }
        finally { server.stop(0) }
    }
}

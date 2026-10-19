package advent.posts.llm

import advent.posts.config.LlmProperties
import com.sun.net.httpserver.HttpServer
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.net.InetSocketAddress
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeepSeekClientTest {
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    @Test
    fun `sends json mode without thinking and reads structured completion`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requestBody = ""
        server.createContext("/chat/completions") { exchange ->
            assertEquals("Bearer test-key", exchange.requestHeaders.getFirst("Authorization"))
            requestBody = exchange.requestBody.readAllBytes().decodeToString()
            val body = """
                {
                  "model": "deepseek-flash",
                  "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": "{\"reply\":\"Готово\"}"},
                    "finish_reason": "stop"
                  }],
                  "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15, "prompt_cache_hit_tokens": 8}
                }
            """.trimIndent().toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        try {
            val result = client(server).complete(
                ChatCompletionRequest(
                    model = "deepseek-flash",
                    messages = listOf(ApiMessage("user", "test")),
                    responseFormat = ResponseFormat.JSON,
                ),
            )

            assertEquals("{\"reply\":\"Готово\"}", result.content)
            assertEquals(8, result.usage?.cachedPromptTokens)
            val sent = mapper.readTree(requestBody)
            assertEquals("json_object", sent["response_format"]["type"].asString())
            assertEquals("disabled", sent["thinking"]["type"].asString())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `provider error keeps status and raw body`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = """{"error":{"message":"Insufficient Balance","type":"unknown_error"}}""".toByteArray()
            exchange.sendResponseHeaders(402, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        try {
            val error = assertFailsWith<LlmException> {
                client(server).complete(ChatCompletionRequest("deepseek-flash", listOf(ApiMessage("user", "test"))))
            }
            assertEquals(402, error.providerStatus)
            assertContains(error.message.orEmpty(), "Insufficient Balance")
            assertContains(error.providerBody.orEmpty(), "unknown_error")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `blank json-mode answer is retried once`() {
        val answers = ArrayDeque(listOf("      ", "{\\\"reply\\\":\\\"Готово\\\"}"))
        val server = completionServer { answers.removeFirst() }
        try {
            val result = client(server).complete(ChatCompletionRequest("deepseek-flash", listOf(ApiMessage("user", "test"))))
            assertEquals("{\"reply\":\"Готово\"}", result.content)
            assertTrue(answers.isEmpty())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `second blank answer becomes an error with provider body`() {
        val server = completionServer { "   " }
        try {
            val error = assertFailsWith<LlmException> {
                client(server).complete(ChatCompletionRequest("deepseek-flash", listOf(ApiMessage("user", "test"))))
            }
            assertEquals("DeepSeek вернул ответ без текста", error.message)
            assertContains(error.providerBody.orEmpty(), "chat.completion")
        } finally {
            server.stop(0)
        }
    }

    private fun completionServer(content: () -> String): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = """{"object":"chat.completion","model":"deepseek-flash",""" +
                """"choices":[{"message":{"content":"${content()}"},"finish_reason":"stop"}]}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    private fun client(server: HttpServer) = DeepSeekClient(
        properties = LlmProperties(
            baseUrl = "http://127.0.0.1:${server.address.port}",
            apiKey = "test-key",
            readTimeout = Duration.ofSeconds(2),
        ),
        objectMapper = mapper,
    )
}

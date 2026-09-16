package advent.day13.llm

import advent.day13.config.OpenRouterProperties
import com.sun.net.httpserver.HttpServer
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.net.InetSocketAddress
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenRouterClientTest {
    @Test
    fun `reads structured completion and provider cost`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { exchange ->
            assertEquals("Bearer test-key", exchange.requestHeaders.getFirst("Authorization"))
            val body = """
                {
                  "model": "openai/gpt-4.1-mini",
                  "provider": "OpenAI",
                  "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": "{\"reply\":\"Готово\"}"},
                    "finish_reason": "stop",
                    "native_finish_reason": "completed"
                  }],
                  "usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 5,
                    "total_tokens": 15,
                    "cost": 0.00001
                  }
                }
            """.trimIndent().toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        try {
            val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
            val client = OpenRouterClient(
                properties = OpenRouterProperties(
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    apiKey = "test-key",
                    readTimeout = Duration.ofSeconds(2),
                ),
                objectMapper = mapper,
            )

            val result = client.complete(
                ChatCompletionRequest(
                    model = "openai/gpt-4.1-mini",
                    messages = listOf(ApiMessage("user", "test")),
                    responseFormat = ResponseFormat.JSON,
                ),
            )

            assertEquals("{\"reply\":\"Готово\"}", result.content)
            assertEquals("OpenAI", result.provider)
            assertEquals(0.00001, result.usage?.costUsd)
            assertEquals(CostSource.PROVIDER, result.usage?.costSource)
        } finally {
            server.stop(0)
        }
    }
}

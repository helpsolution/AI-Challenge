package advent.day11.llm

import com.sun.net.httpserver.HttpServer
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpJsonTest {
    @Test
    fun `request ends within configured timeout when provider stalls`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat") { exchange ->
            Thread.sleep(1500)
            exchange.close()
        }
        server.start()

        try {
            val endpoint = URI.create("http://127.0.0.1:${server.address.port}/chat")
            val client = HttpJson(Duration.ofSeconds(1))
            var message = ""
            val elapsed = measureTimeMillis {
                message = assertFailsWith<LlmException> {
                    client.post(endpoint, mapOf("Content-Type" to "application/json"), "{}",
                        Duration.ofMillis(200), LoggerFactory.getLogger(javaClass))
                }.message.orEmpty()
            }
            assertContains(message, "Провайдер не ответил")
            assertTrue(elapsed < 1200, "Запрос занял $elapsed мс")
        } finally {
            server.stop(0)
        }
    }
}

package advent.rag.reranking

import advent.rag.agent.PromptBuilder
import advent.rag.agent.Source
import advent.rag.chunking.Chunk
import advent.rag.embedding.QueryEmbedding
import advent.rag.http.HttpExchange
import advent.rag.knowledge.ScoredChunk
import advent.rag.retrieval.Retrieval
import advent.rag.trace.RerankStep
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
import kotlin.time.Duration.Companion.ZERO

class RerankerClientTest {
    private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val candidates = (0..2).map { i ->
        ScoredChunk(Chunk("doc-$i#0", "doc-$i.md", "Лекция $i", null, "Раздел $i", index = 0,
            start = 0, end = 7, text = "Текст $i"), 0.9 - i * 0.1)
    }
    private val response = """{"model":"bge-reranker-v2-m3","results":[
        {"index":2,"relevance_score":-4.0},{"index":0,"relevance_score":2.5},{"index":1,"relevance_score":6.95}]}"""

    @Test fun `maps response indices and sorts raw scores while preserving cosine`() {
        withServer(response) { client, requests ->
            val result = client.rerank("Вопрос", candidates)
            assertEquals(listOf("doc-1#0", "doc-0#0", "doc-2#0"), result.ranking.map { it.chunk.chunkId })
            assertEquals(listOf(6.95, 2.5, -4.0), result.ranking.map { it.rerankScore })
            assertEquals(candidates[1].score, result.ranking[0].score)
            val sent = json.readTree(requests.single())
            assertEquals("Вопрос", sent["query"].asString())
            assertEquals(3, sent["top_n"].asInt())
            assertEquals(candidates[0].chunk.embeddingText, sent["documents"][0].asString())
            assertEquals(200, result.exchange.status)
            assertEquals(response, result.exchange.responseBody)
        }
    }

    @Test fun `final selection prompt citations and trace use reranked order`() {
        withServer(response) { client, _ ->
            val exchange = HttpExchange("POST", "local", emptyMap(), "", 200, "", 5)
            val baseline = Retrieval(QueryEmbedding("Вопрос", floatArrayOf(1f), null, exchange), candidates, 1, ZERO, 3)
            val selected = baseline.copy(reranking = client.rerank("Вопрос", candidates))
            assertEquals(candidates, selected.ranking)
            assertEquals(candidates[0], baseline.chunks.single())
            assertEquals("doc-1#0", selected.chunks.single().chunk.chunkId)
            val prompt = PromptBuilder().answer("Вопрос", emptyList(), selected.chunks).last().content
            assertTrue(prompt.contains("[1] Лекция 1"))
            assertFalse(prompt.contains("Лекция 0"))
            val source = Source.of(1, selected.chunks.single(), true)
            assertEquals(6.95, source.rerankScore)
            assertEquals("doc-1#0", source.chunkId)
            val trace = RerankStep.of(selected)
            assertEquals(2, trace.candidates.first().originalRank)
            assertEquals(1, trace.candidates.count { it.selected })
            assertEquals(3, trace.candidates.size)
            assertEquals(selected.durationMs, 5 + selected.reranking!!.exchange.durationMs)
            assertFalse(RerankStep.of(baseline).enabled)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        """{"results":[]}""",
        """{"results":[{"index":0,"relevance_score":1},{"index":0,"relevance_score":2},{"index":2,"relevance_score":3}]}""",
        """{"results":[{"index":0,"relevance_score":1},{"index":1,"relevance_score":2},{"index":3,"relevance_score":3}]}""",
        """{"results":[{"relevance_score":1},{"index":1,"relevance_score":2},{"index":2,"relevance_score":3}]}""",
        """{"results":[{"index":0,"relevance_score":1},{"index":1},{"index":2,"relevance_score":3}]}""",
        """{"results":[{"index":0,"relevance_score":1},{"index":1,"relevance_score":1e999},{"index":2,"relevance_score":3}]}""",
        "not-json",
    ])
    fun `rejects malformed partial duplicate and nonfinite results`(body: String) {
        withServer(body) { client, _ -> assertThrows(RerankerException::class.java) { client.rerank("Вопрос", candidates) } }
    }

    @Test fun `http failure is explicit and does not silently substitute cosine ranking`() {
        withServer("context too long", 400) { client, _ ->
            val error = assertThrows(RerankerException::class.java) { client.rerank("Вопрос", candidates) }
            assertTrue(error.message!!.contains("400"))
        }
    }

    @Test fun `unavailable service suggests disabling reranking`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        server.start()
        server.stop(0)
        val error = assertThrows(RerankerException::class.java) { client(port).rerank("Вопрос", candidates) }
        assertTrue(error.message!!.contains("выключите реранкинг"))
    }

    private fun client(port: Int) = RerankerClient(RestClient.builder(), json,
        RerankerProperties("http://127.0.0.1:$port", Duration.ofSeconds(5), true))

    private fun withServer(body: String, status: Int = 200, test: (RerankerClient, List<String>) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<String>()
        server.createContext("/v1/rerank") { exchange ->
            exchange.use {
                requests += it.requestBody.bufferedReader().readText()
                val bytes = body.toByteArray()
                it.responseHeaders.add("Content-Type", "application/json")
                it.sendResponseHeaders(status, bytes.size.toLong())
                it.responseBody.write(bytes)
            }
        }
        server.start()
        try { test(client(server.address.port), requests) } finally { server.stop(0) }
    }
}

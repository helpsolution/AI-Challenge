package advent.rag

import advent.rag.agent.*
import advent.rag.chunking.Chunk
import advent.rag.grounding.GroundingChecker
import advent.rag.http.HttpExchange
import advent.rag.llm.*
import advent.rag.reranking.RerankedChunk
import advent.rag.trace.Recorder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

class VerifiedGeneratorTest {
    private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val context = listOf(RerankedChunk(Chunk("1", "doc", "doc", null, null, index = 0,
        start = 0, end = 20, text = "Порт сервера — 8312."), 1.0, 3.0))
    private val good = """{"status":"answer","answer":"Порт 8312 [1]","quotes":[{"fragment":1,"quote":"Порт сервера — 8312."}]}"""
    private val bad = """{"status":"answer","answer":"Порт 9999 [1]","quotes":[{"fragment":1,"quote":"Порт 9999"}]}"""
    @Test fun `single repair keeps same sources and aggregates token usage`() {
        val model = FakeModel(listOf(bad, good))
        val trace = Recorder("test")
        val result = VerifiedGenerator(model, GroundingChecker(json), PromptBuilder(json))
            .generate(listOf(ChatMessage("user", "Исходный источник")), context, trace)
        assertEquals(AnswerStatus.ANSWERED, result.checked.status)
        assertEquals(2, model.calls.size)
        assertEquals("Исходный источник", model.calls[1][0].content)
        assertTrue(model.calls[1].last().content.contains("Цитаты нет"))
        assertEquals(20, result.completion.promptTokens)
        assertEquals(10, result.completion.completionTokens)
    }
    @Test fun `second invalid answer stops without further retries`() {
        val model = FakeModel(listOf(bad, bad))
        val result = VerifiedGenerator(model, GroundingChecker(json), PromptBuilder(json))
            .generate(emptyList(), context, Recorder("test"))
        assertEquals(AnswerStatus.UNVERIFIED, result.checked.status); assertEquals(2, model.calls.size)
    }
    @Test fun `valid answer does not trigger repair`() {
        val model = FakeModel(listOf(good))
        VerifiedGenerator(model, GroundingChecker(json), PromptBuilder(json)).generate(emptyList(), context, Recorder("test"))
        assertEquals(1, model.calls.size)
    }
    private class FakeModel(private val replies: List<String>): ChatModel {
        override val provider = Provider.CLOUD
        override val model = "test"
        val calls = mutableListOf<List<ChatMessage>>()
        override fun complete(messages: List<ChatMessage>, schema: JsonNode, maxTokens: Int, temperature: Double?): Completion {
            calls += messages
            return Completion(replies[calls.size - 1], model, 10, 5, "stop",
                HttpExchange("POST", "test", emptyMap(), "", 200, "", 1), null)
        }
    }
}

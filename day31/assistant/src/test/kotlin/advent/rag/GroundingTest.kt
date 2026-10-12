package advent.rag

import advent.rag.agent.AnswerStatus
import advent.rag.chunking.Chunk
import advent.rag.grounding.GroundingChecker
import advent.rag.http.HttpExchange
import advent.rag.llm.Completion
import advent.rag.reranking.RerankedChunk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

class GroundingTest {
    private val checker = GroundingChecker(JsonMapper.builder().addModule(KotlinModule.Builder().build()).build())
    private val context = listOf(RerankedChunk(Chunk("1", "day31/docs/api.md", "API", null, null,
        index = 0, start = 0, end = 34, text = "MCP-сервер слушает порт 8312."), 1.0, 3.0))
    private fun check(text: String, reason: String = "stop") = checker.check(Completion(text, "test", 1, 1, reason,
        HttpExchange("POST", "test", emptyMap(), "", 200, "", 0), null), context)
    @Test fun `matching quote and citation are accepted`() {
        assertEquals(AnswerStatus.ANSWERED, check("""{"status":"answer","answer":"Порт 8312 [1]","quotes":[{"fragment":1,"quote":"MCP-сервер слушает порт 8312."}]}""").status)
    }
    @Test fun `invented quote and uncited answer are rejected`() {
        assertEquals(AnswerStatus.UNVERIFIED, check("""{"status":"answer","answer":"Порт 9999 [1]","quotes":[{"fragment":1,"quote":"Порт 9999"}]}""").status)
        assertEquals(AnswerStatus.UNVERIFIED, check("""{"status":"answer","answer":"Порт 8312","quotes":[{"fragment":1,"quote":"порт 8312"}]}""").status)
    }
    @Test fun `unknown is accepted but truncated answer is rejected`() {
        val unknown = """{"status":"unknown","answer":"","quotes":[]}"""
        assertEquals(AnswerStatus.NO_ANSWER, check(unknown).status)
        assertEquals(AnswerStatus.UNVERIFIED, check(unknown, "length").status)
    }
}

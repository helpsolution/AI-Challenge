package advent.rag

import advent.rag.agent.*
import advent.rag.llm.ChatMessage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RequestPlannerTest {
    private val scopes = listOf("day19", "day28", "day30", "day31")
    @Test fun `branch while working does not require documentation`() {
        val plan = RequestPlanner.plan(HelpRequest("/help На какой git-ветке я сейчас работаю?", "day31"), scopes)
        assertTrue(plan.contextOnly)
        assertEquals(listOf("git_branch"), plan.tools)
    }
    @Test fun `explicit day overrides selected scope`() {
        assertEquals("day30", RequestPlanner.plan(HelpRequest("/help Как устроена очередь в day30?", "day28"), scopes).scope)
    }
    @Test fun `multiple days and unknown days need clarification`() {
        assertNotNull(RequestPlanner.plan(HelpRequest("/help Сравни day28 и day30"), scopes).clarification)
        assertNotNull(RequestPlanner.plan(HelpRequest("/help Как устроен day999?"), scopes).clarification)
    }
    @Test fun `status and files route through MCP without model`() {
        val status = RequestPlanner.plan(HelpRequest("/help Что изменено в рабочем дереве?"), scopes)
        assertTrue(status.contextOnly); assertTrue("git_status" in status.tools)
        val files = RequestPlanner.plan(HelpRequest("/help Какие файлы есть в day31?"), scopes)
        assertTrue(files.contextOnly); assertTrue("list_files" in files.tools)
    }
    @Test fun `structure uses documentation and live files`() {
        val plan = RequestPlanner.plan(HelpRequest("/help Как устроен day31 и какие в нём модули?"), scopes)
        assertFalse(plan.contextOnly); assertTrue("list_files" in plan.tools)
    }
    @Test fun `followup search uses user question rather than previous answer`() {
        val plan = RequestPlanner.plan(HelpRequest("/help А какие ограничения?", "day30", listOf(
            ChatMessage("user", "/help Как устроена очередь в day30?"), ChatMessage("assistant", "Выдуманный ответ"))), scopes)
        assertTrue(plan.query.contains("очередь")); assertFalse(plan.query.contains("Выдуманный"))
    }
}

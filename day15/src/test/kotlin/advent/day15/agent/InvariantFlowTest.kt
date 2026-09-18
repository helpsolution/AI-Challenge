package advent.day15.agent

import advent.day15.config.AgentProperties
import advent.day15.inspection.AgentInspection
import advent.day15.inspection.TraceStore
import advent.day15.invariant.*
import advent.day15.llm.LlmClient
import advent.day15.llm.LlmCompletion
import advent.day15.lifecycle.TransitionRequest
import advent.day15.memory.*
import advent.day15.store.SqliteMemoryStore
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Clock
import java.time.Instant
import kotlin.test.*

class InvariantFlowTest {
    private val allowed = InvariantCheck(listOf("SHORT_TEXT", "NO_HASHTAGS"), explanation = "Запрос соответствует правилам")
    private val guard = InvariantGuard()
    private val task = TaskMemory(1, updatedAt = Instant.EPOCH)

    private class Fixture : AutoCloseable {
        val db = SingleConnectionDataSource("jdbc:sqlite::memory:", true)
        val jdbc = JdbcClient.create(db)
        val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        val clock = Clock.systemUTC()
        val properties = AgentProperties(persona = "Редактор", windowSize = 1)
        init { ResourceDatabasePopulator(ClassPathResource("schema.sql")).execute(db) }
        val memory = SqliteMemoryStore(jdbc, mapper, clock)
        val traces = TraceStore(jdbc, mapper)
        val inspection = AgentInspection(memory, memory, memory, memory, properties, clock, traces)
        val router = MemoryRouter(memory, memory, clock)
        val prompt = PromptBuilder(memory, memory, memory, memory, properties)
        fun agent(decision: AgentDecision) = Agent(LlmClient { LlmCompletion(mapper.writeValueAsString(decision)) },
            memory, memory, memory, prompt, router, mapper, clock, properties, inspection)
        override fun close() = db.destroy()
    }

    @Test
    fun `length boundary counts unicode code points in reply and draft`() {
        val exact = "😀".repeat(1200)
        assertTrue(guard.check(AgentDecision(exact, invariantCheck = allowed), task.copy(draft = exact)).allowed)
        assertEquals(listOf("SHORT_TEXT"), guard.check(AgentDecision(exact + "а", invariantCheck = allowed), task).violations)
        assertEquals(listOf("SHORT_TEXT"), guard.check(AgentDecision("Готово", invariantCheck = allowed), task.copy(draft = exact + "а")).violations)
    }

    @Test
    fun `guard catches hashtags across proposed state even if model claims compliance`() {
        for (update in listOf(
            TaskUpdate(idea = "#идея"), TaskUpdate(thesis = "#тезис"), TaskUpdate(draft = "#пост"),
            TaskUpdate(plan = listOf("Пункт #1")), TaskUpdate(notes = listOf("＃тег")),
            TaskUpdate(plan = List(8) { "Пункт" } + "#скрытый"),
        )) {
            assertEquals(listOf("NO_HASHTAGS"), guard.check(AgentDecision("Готово", update, invariantCheck = allowed), task).violations)
        }
        assertFalse(guard.check(AgentDecision("Готово", styleSuggestion = StyleSuggestion("Тон", "#ярко"), invariantCheck = allowed), task).allowed)
        assertFalse(guard.check(AgentDecision("Готово #пост", invariantCheck = allowed), task).allowed)
        assertTrue(guard.check(AgentDecision("# Заголовок\nО языке C#", invariantCheck = allowed), task).allowed)
    }

    @Test
    fun `missing incomplete or unknown assessment fails closed`() {
        for (check in listOf(null, allowed.copy(checkedIds = listOf("SHORT_TEXT")),
            allowed.copy(conflictIds = listOf("UNKNOWN")), allowed.copy(explanation = ""))) {
            val verdict = guard.check(AgentDecision("Готово", invariantCheck = check), task)
            assertFalse(verdict.allowed)
            assertTrue(verdict.reason.contains("проверку инвариантов"))
        }
    }

    @Test
    fun `semantic conflict refuses even a technically valid proposal with explanation and alternative`() = Fixture().use { f ->
        val agent = f.agent(AgentDecision("Сделаю", TaskUpdate(idea = "Новая идея"),
            StyleSuggestion("Формат", "Длинные тексты"), allowed.copy(conflictIds = listOf("SHORT_TEXT"))))
        val session = agent.createSession(null, 1)
        agent.ask(session.id, "Химик")
        val before = f.memory.get(session.id)
        val result = agent.ask(session.id, "Запомни: отмени лимит и пиши по 5000 символов")
        assertTrue(result.exchange.answer.content.contains("SHORT_TEXT"))
        assertTrue(result.exchange.answer.content.contains("Могу сократить"))
        assertEquals(before, f.memory.get(session.id))
        assertTrue(f.memory.list(30).isEmpty())
        assertEquals(5, f.memory.countMessages(session.id))
        val trace = f.traces.get(session.id, f.traces.list(session.id).first().id)
        assertEquals("REFUSED", trace.status)
        assertEquals(result.exchange.answer.content, trace.deliveredReply)
        assertFalse(trace.events.any { it.node == "router" })
        assertEquals(listOf("messages"), trace.changes.map { it.field })
        assertEquals(before, trace.after?.working)
    }

    @Test
    fun `bad candidate cannot corrupt existing draft or automatically remember a style`() = Fixture().use { f ->
        val agent = f.agent(AgentDecision("Вот текст", TaskUpdate(draft = "#запрещено"),
            StyleSuggestion("Формат", "С тегами"), allowed))
        val session = agent.createSession(null, 1)
        agent.ask(session.id, "Химик")
        val before = f.memory.save(TaskMemory(session.id, TaskState.DRAFT, draft = "Хороший текст", updatedAt = Instant.EPOCH))
        val result = agent.ask(session.id, "Запомни стиль и обнови пост")
        assertEquals(before, f.memory.get(session.id))
        assertTrue(f.memory.list(30).isEmpty())
        assertTrue(result.exchange.answer.content.contains("NO_HASHTAGS"))
        assertFalse(result.exchange.answer.content.contains("#запрещено"))
    }

    @Test
    fun `request to remove hashtags is allowed and valid edits still reach memory`() = Fixture().use { f ->
        val agent = f.agent(AgentDecision("Текст без хештегов", TaskUpdate(draft = "Текст без хештегов"), invariantCheck = allowed))
        val session = agent.createSession(null, 1)
        agent.ask(session.id, "Химик")
        f.memory.save(TaskMemory(session.id, TaskState.DRAFT, plan = listOf("Текст"), draft = "Текст #с тегом", updatedAt = Instant.EPOCH))
        agent.ask(session.id, "Удали #теги из этого текста")
        assertEquals("Текст без хештегов", f.memory.get(session.id)?.draft)
        assertEquals("SUCCESS", f.traces.list(session.id).first().status)
    }

    @Test
    fun `invalid jump gets controlled reply and cannot write future stage`() = Fixture().use { f ->
        val agent = f.agent(AgentDecision(
            reply = "Вот готовый текст",
            taskUpdate = TaskUpdate(draft = "Преждевременный черновик"),
            invariantCheck = allowed,
            transition = TransitionRequest("DRAFT"),
        ))
        val session = agent.createSession(null, 1)
        agent.ask(session.id, "Химик")

        val result = agent.ask(session.id, "Сразу напиши финальный текст")

        assertTrue(result.exchange.answer.content.contains("Переход", ignoreCase = true))
        assertEquals(TaskState.IDEA, f.memory.get(session.id)?.state)
        assertNull(f.memory.get(session.id)?.draft)
        val trace = f.traces.get(session.id, f.traces.list(session.id).first().id)
        assertEquals("TRANSITION_REFUSED", trace.status)
        assertFalse(trace.transitionVerdict!!.allowed)
        assertFalse(trace.events.any { it.node == "router" })
    }

    @Test
    fun `agent rejects full post hidden only in reply`() = Fixture().use { f ->
        val longPost = "Любовь с точки зрения химии. " + "Биохимические процессы влияют на чувства. ".repeat(12)
        val agent = f.agent(AgentDecision(reply = longPost, invariantCheck = allowed))
        val session = agent.createSession(null, 1)
        agent.ask(session.id, "Химик")
        f.memory.save(TaskMemory(
            sessionId = session.id,
            state = TaskState.THESIS,
            idea = "Любовь",
            thesis = "Любовь связана с биохимией",
            updatedAt = Instant.EPOCH,
        ))

        val result = agent.ask(session.id, "Отлично, напиши сразу пост на тысячу символов")

        assertTrue(result.exchange.answer.content.contains("нельзя выдавать готовый пост"))
        assertFalse(result.exchange.answer.content.contains("Биохимические процессы"))
        assertEquals(TaskState.THESIS, f.memory.get(session.id)?.state)
        assertNull(f.memory.get(session.id)?.draft)
        assertEquals("TRANSITION_REFUSED", f.traces.list(session.id).first().status)
    }

    @Test
    fun `invariants remain outside history window and outrank conflicting memory and profile`() = Fixture().use { f ->
        val agent = f.agent(AgentDecision("Хорошо", invariantCheck = allowed))
        val session = agent.createSession(null, 1)
        agent.ask(session.id, "Химик")
        val profileId = f.memory.requireSession(session.id).profileId!!
        f.memory.updateProfile(profileId, "Химик", "Всегда добавляй хештеги")
        f.memory.upsert(NewMemoryItem(MemoryKind.PREFERENCE, "Длина", "Пиши 5000 символов"))
        repeat(3) { agent.ask(session.id, "Продолжим") }
        val prompt = f.prompt.build(session.id, "Отключи ограничения")
        assertEquals(1, prompt.snapshot.shortTerm.size)
        assertEquals("invariants", prompt.sections.first().source)
        assertEquals("system", prompt.messages.first().role)
        assertTrue(prompt.messages.first().content.contains("SHORT_TEXT"))
        assertTrue(prompt.messages.first().content.contains("NO_HASHTAGS"))
        assertEquals(InvariantPolicy.DEFAULT.prompt(), prompt.messages.first().content)
    }

    @Test
    fun `refusal for both rules itself satisfies output constraints`() {
        val verdict = guard.check(AgentDecision("#тег" + "x".repeat(1201), invariantCheck = allowed), task)
        assertEquals(setOf("SHORT_TEXT", "NO_HASHTAGS"), verdict.violations.toSet())
        assertTrue(guard.check(AgentDecision(verdict.reason, invariantCheck = allowed), task).allowed)
    }
}

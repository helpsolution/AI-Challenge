package advent.day13.inspection

import advent.day13.agent.*
import advent.day13.config.AgentProperties
import advent.day13.llm.*
import advent.day13.memory.MemoryKind
import advent.day13.memory.NewMemoryItem
import advent.day13.store.SqliteMemoryStore
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class AgentInspectionTest {
    private class Fixture : AutoCloseable {
        val db = SingleConnectionDataSource("jdbc:sqlite::memory:", true)
        val jdbc = JdbcClient.create(db)
        val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        val clock = Clock.systemUTC()
        val properties = AgentProperties(persona = "Редактор", windowSize = 2)
        init {
            jdbc.sql("PRAGMA foreign_keys = ON").update()
            ResourceDatabasePopulator(ClassPathResource("schema.sql")).execute(db)
        }
        val memory = SqliteMemoryStore(jdbc, mapper, clock)
        val traces = TraceStore(jdbc, mapper)
        val inspection = AgentInspection(memory, memory, memory, memory, properties, clock, traces)
        val router = MemoryRouter(memory, memory, clock)
        fun agent(llm: LlmClient) = Agent(llm, memory, memory, memory,
            PromptBuilder(memory, memory, memory, memory, properties), router, mapper, clock, properties, inspection)
        fun latest(id: Long) = traces.get(id, traces.list(id).first().id)
        override fun close() = db.destroy()
    }

    @Test
    fun `profile selection records local path without contacting model`() = Fixture().use { f ->
        val agent = f.agent { error("Model must not be called") }
        val session = agent.createSession("Проверка профиля", 2)
        agent.ask(session.id, "Давай химика")
        val trace = f.latest(session.id)
        assertEquals("SUCCESS", trace.status)
        assertEquals(listOf("input", "profileSelect", "output"), trace.events.map { it.node })
        assertNull(trace.request)
        assertNull(trace.before.profile)
        assertEquals("Химик", trace.after?.profile?.name)
        assertTrue(trace.changes.any { it.field == "profile" })
    }

    @Test
    fun `trace preserves exact request old context usage and actual memory changes`() = Fixture().use { f ->
        val reply = """{"reply":"Сформулируем тезис","taskUpdate":{"stage":"THESIS","idea":"Практика AI"},"styleSuggestion":{"key":"Тон","value":"Спокойный"}}"""
        var sent: ChatCompletionRequest? = null
        val agent = f.agent { request ->
            sent = request
            LlmCompletion(reply, reasoning = "not persisted", model = "test-model", provider = "test",
                usage = TokenUsage(120, 30, 150))
        }
        val session = agent.createSession("Проверка контекста", 2)
        agent.ask(session.id, "Химик")
        agent.ask(session.id, "Хочу написать про практику AI")
        val trace = f.latest(session.id)
        assertEquals(sent, trace.request)
        assertEquals(2, trace.prompt?.snapshot?.shortTerm?.size)
        assertEquals(3, trace.before.messageCount)
        assertEquals(5, trace.after?.messageCount)
        assertEquals(150, trace.completion?.usage?.totalTokens)
        assertNull(trace.completion?.reasoning)
        assertEquals("Практика AI", trace.after?.working?.idea)
        assertEquals(0, trace.after?.longTermTotal)
        assertNotNull(trace.after?.working?.styleSuggestion)
        assertEquals(listOf("input", "prompt", "llm", "parser", "router", "output"), trace.events.map { it.node })
        assertEquals(trace.request?.messages?.sumOf { it.content.length }, trace.prompt?.charsSent)
        assertEquals(trace.prompt?.messages?.size, trace.prompt?.sections?.size)

        f.inspection.memoryAction(session.id, "Подтверждение правила") { f.router.acceptStyleSuggestion(session.id) }
        val confirmation = f.latest(session.id)
        assertEquals("MEMORY", confirmation.kind)
        assertEquals(1, confirmation.after?.longTermTotal)
        assertNull(confirmation.after?.working?.styleSuggestion)
        assertTrue(confirmation.changes.any { it.field == "longTerm" })
        f.memory.updateProfile(trace.before.profile!!.id, "Новый химик", "Другое описание")
        val reread = TraceStore(f.jdbc, f.mapper).get(session.id, trace.id)
        assertEquals("Химик", reread.prompt?.snapshot?.profile?.name)
        assertEquals(0, reread.after?.longTermTotal)
        assertEquals(sent, reread.request)
    }

    @Test
    fun `malformed reply and provider failure remain visible without fabricated memory writes`() = Fixture().use { f ->
        var invalidJson = true
        val agent = f.agent { if (invalidJson) LlmCompletion("not json") else throw LlmException("Provider unavailable") }
        val session = agent.createSession("Ошибки", 2)
        agent.ask(session.id, "Химик")
        assertFailsWith<LlmException> { agent.ask(session.id, "Тест JSON") }
        val invalid = f.latest(session.id)
        assertEquals("ERROR", invalid.status)
        assertEquals("parser", invalid.events.last().node)
        assertEquals("not json", invalid.completion?.content)
        assertTrue(invalid.changes.isEmpty())
        assertEquals(3, f.memory.countMessages(session.id))
        invalidJson = false
        assertFailsWith<LlmException> { agent.ask(session.id, "Тест провайдера") }
        val failure = f.latest(session.id)
        assertEquals("llm", failure.events.last().node)
        assertNotNull(failure.request)
        assertNull(failure.completion)
        assertEquals("Provider unavailable", failure.error)
    }

    @Test
    fun `pending call is observable before completion and deleted session removes traces only`() = Fixture().use { f ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val agent = f.agent {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                LlmCompletion("""{"reply":"Готово"}""")
            }
            val first = agent.createSession("Первый", 2)
            val second = agent.createSession("Второй", 2)
            agent.ask(first.id, "Химик")
            val pending = executor.submit { agent.ask(first.id, "Новый запрос") }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val running = f.latest(first.id)
            assertEquals("RUNNING", running.status)
            assertEquals("llm", running.events.last().node)
            assertNotNull(running.request)
            assertFailsWith<IllegalArgumentException> { f.traces.get(second.id, running.id) }
            release.countDown()
            pending.get(10, TimeUnit.SECONDS)
            assertEquals("SUCCESS", f.latest(first.id).status)
            f.memory.upsert(NewMemoryItem(MemoryKind.PREFERENCE, "Стиль", "Коротко", sourceSessionId = first.id))
            f.memory.deleteSession(first.id)
            assertTrue(f.traces.list(first.id).isEmpty())
            assertEquals(1, f.memory.list(10).size)
            assertNotNull(f.memory.session(second.id))
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test
    fun `unfinished traces become interrupted after restart`() = Fixture().use { f ->
        val agent = f.agent { error("Unused") }
        val session = agent.createSession("Перезапуск", 2)
        f.inspection.begin(session.id, "Запрос")
        f.traces.markInterrupted()
        assertEquals("INTERRUPTED", f.latest(session.id).status)
    }
}

package advent.day11.agent

import advent.day11.chat.Message
import advent.day11.chat.Role
import advent.day11.chat.Session
import advent.day11.config.AgentProperties
import advent.day11.llm.LlmClient
import advent.day11.llm.LlmCompletion
import advent.day11.llm.ResponseFormat
import advent.day11.memory.LongTermMemory
import advent.day11.memory.MemoryItem
import advent.day11.memory.NewMemoryItem
import advent.day11.memory.ShortTermMemory
import advent.day11.memory.StyleSuggestion
import advent.day11.memory.TaskMemory
import advent.day11.memory.TaskState
import advent.day11.memory.WorkingMemory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryFlowTest {
    private val now = Instant.parse("2026-09-14T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `editor advances through four stages and can return to thesis`() {
        val store = TestMemoryStore(now)
        val router = MemoryRouter(store, store, clock)
        store.save(TaskMemory(1, updatedAt = now))

        router.observe(1, "Хочу написать про обучение AI", AgentDecision(
            reply = "В чем главная мысль?",
            taskUpdate = TaskUpdate(stage = "THESIS", idea = "Обучение AI"),
        ))
        assertEquals(TaskState.THESIS, store.get(1)?.state)
        assertNull(store.get(1)?.thesis)

        router.observe(1, "Да, это мой тезис", AgentDecision(
            reply = "Предлагаю план",
            taskUpdate = TaskUpdate(stage = "PLAN", thesis = "Практика важнее чтения"),
        ))
        assertEquals(TaskState.PLAN, store.get(1)?.state)

        router.observe(1, "План подходит", AgentDecision(
            reply = "Вот черновик",
            taskUpdate = TaskUpdate(stage = "DRAFT", plan = listOf("Опыт", "Вывод"), draft = "Первый вариант поста"),
        ))
        assertEquals(TaskState.DRAFT, store.get(1)?.state)
        assertEquals("Первый вариант поста", store.get(1)?.draft)

        router.observe(1, "Тезис другой", AgentDecision(
            reply = "Уточним мысль",
            taskUpdate = TaskUpdate(stage = "THESIS", thesis = "Нужна практика с обратной связью"),
        ))
        assertEquals(TaskState.THESIS, store.get(1)?.state)
        assertTrue(store.get(1)?.plan.isNullOrEmpty())
        assertNull(store.get(1)?.draft)
        assertTrue(store.list(10).isEmpty())
    }

    @Test
    fun `style suggestion stays in working memory until confirmed`() {
        val store = TestMemoryStore(now)
        val router = MemoryRouter(store, store, clock)
        store.save(TaskMemory(1, updatedAt = now))

        router.observe(1, "Финал слишком назидательный", AgentDecision(
            reply = "Сделаю спокойнее",
            styleSuggestion = StyleSuggestion("Финал", "Без назидательного вывода"),
        ))
        assertTrue(store.list(10).isEmpty())
        assertNotNull(store.get(1)?.styleSuggestion)

        router.acceptStyleSuggestion(1)
        assertNull(store.get(1)?.styleSuggestion)
        assertEquals("Без назидательного вывода", store.list(10).single().value)

        router.observe(1, "Запомни: пиши от первого лица", AgentDecision(
            reply = "Запомнил",
            styleSuggestion = StyleSuggestion("Лицо", "От первого лица"),
        ))
        assertEquals(2, store.list(10).size)
        assertNull(store.get(1)?.styleSuggestion)
    }

    @Test
    fun `prompt uses short window plus separate task and long term rules`() {
        val store = TestMemoryStore(now)
        store.createSession("Пост про AI", 2)
        store.saveMessage(1, Role.USER, "Первое сообщение")
        store.saveMessage(1, Role.ASSISTANT, "Первый ответ")
        store.saveMessage(1, Role.USER, "Последний вопрос")
        store.save(TaskMemory(1, TaskState.PLAN, idea = "AI", thesis = "Практика нужна", updatedAt = now))
        store.upsert(NewMemoryItem(advent.day11.memory.MemoryKind.PREFERENCE, "Тон", "Спокойный"))

        val prompt = PromptBuilder(store, store, store, AgentProperties(persona = "Редактор"))
            .build(1, "Новый вопрос")
        assertEquals(2, prompt.snapshot.shortTerm.size)
        assertFalse(prompt.messages.any { it.content == "Первое сообщение" })
        assertTrue(prompt.messages.any { it.content.contains("Тезис: Практика нужна") })
        assertTrue(prompt.messages.any { it.content.contains("Тон: Спокойный") })
    }

    @Test
    fun `agent starts a post and separates visible reply from task update`() {
        val store = TestMemoryStore(now)
        val properties = AgentProperties(persona = "Редактор")
        val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        val llm = LlmClient { request ->
            assertEquals(ResponseFormat.JSON, request.responseFormat)
            assertTrue(request.messages.any { it.content.contains("Этап: IDEA") })
            LlmCompletion("""{"reply":"Какой опыт хочешь описать?","taskUpdate":{"stage":"THESIS","idea":"Пост про практику AI"},"styleSuggestion":null}""")
        }
        val router = MemoryRouter(store, store, clock)
        val agent = Agent(llm, store, store, PromptBuilder(store, store, store, properties),
            router, mapper, clock, properties)

        val session = agent.createSession(null, 3)
        assertEquals("Новый пост", session.title)
        assertEquals(TaskState.IDEA, store.get(session.id)?.state)
        assertTrue(store.history(session.id).single().content.contains("О чем хочешь написать"))

        val result = agent.ask(session.id, "Хочу рассказать про практику AI")
        assertEquals("Какой опыт хочешь описать?", result.exchange.answer.content)
        assertEquals(TaskState.THESIS, store.get(session.id)?.state)
        assertEquals("Пост про практику AI", store.get(session.id)?.idea)
        assertTrue(store.list(10).isEmpty())
    }

    private class TestMemoryStore(private val now: Instant) : ShortTermMemory, WorkingMemory, LongTermMemory {
        private val sessions = mutableListOf<Session>()
        private val messages = mutableListOf<Message>()
        private val tasks = mutableMapOf<Long, TaskMemory>()
        private val items = mutableListOf<MemoryItem>()

        override fun sessions() = sessions.toList()
        override fun session(id: Long) = sessions.find { it.id == id }
        override fun createSession(title: String, windowSize: Int): Session =
            Session((sessions.size + 1).toLong(), title, windowSize, now).also { sessions += it }
        override fun deleteSession(id: Long) { sessions.removeIf { it.id == id } }
        override fun history(sessionId: Long) = messages.filter { it.sessionId == sessionId }
        override fun recent(sessionId: Long, limit: Int) = history(sessionId).takeLast(limit)
        override fun countMessages(sessionId: Long) = history(sessionId).size
        override fun saveMessage(sessionId: Long, role: Role, content: String): Message =
            Message((messages.size + 1).toLong(), sessionId, role, content, now).also { messages += it }
        override fun get(sessionId: Long) = tasks[sessionId]
        override fun save(memory: TaskMemory) = memory.also { tasks[it.sessionId] = it }
        override fun clear(sessionId: Long) { tasks.remove(sessionId) }
        override fun list(limit: Int) = items.take(limit)
        override fun upsert(item: NewMemoryItem): MemoryItem =
            MemoryItem((items.size + 1).toLong(), item.kind, item.key, item.value, item.confidence,
                item.sourceSessionId, now, now).also { items += it }
        override fun deleteItem(id: Long) { items.removeIf { it.id == id } }
    }
}

package advent.day15.store

import advent.day15.agent.AgentDecision
import advent.day15.agent.MemoryRouter
import advent.day15.lifecycle.TaskLifecycle
import advent.day15.lifecycle.TransitionRequest
import advent.day15.memory.TaskMemory
import advent.day15.memory.TaskState
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Files
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatePersistenceTest {
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val clock = Clock.systemUTC()

    @Test
    fun `task continues from persisted stage after application pause`() {
        val file = Files.createTempFile("day15-pause-", ".db")
        try {
            val firstDb = dataSource(file.toString())
            ResourceDatabasePopulator(ClassPathResource("schema.sql")).execute(firstDb)
            val first = SqliteMemoryStore(JdbcClient.create(firstDb), mapper, clock)
            val session = first.createSession("Пост", 4)
            first.save(TaskMemory(
                sessionId = session.id,
                state = TaskState.PLAN,
                idea = "Практика AI",
                thesis = "Навык появляется через практику",
                plan = listOf("Опыт", "Вывод"),
                updatedAt = clock.instant(),
            ))
            firstDb.destroy()

            val resumedDb = dataSource(file.toString())
            val resumed = SqliteMemoryStore(JdbcClient.create(resumedDb), mapper, clock)
            val restored = resumed.get(session.id)!!
            assertEquals(TaskState.PLAN, restored.state)
            assertEquals(listOf("Опыт", "Вывод"), restored.plan)

            val router = MemoryRouter(resumed, resumed, clock)
            val decision = AgentDecision("Начинаю черновик", transition = TransitionRequest("DRAFT"))
            val evaluated = TaskLifecycle().evaluate(restored, router.preview(session.id, decision), decision.transition)
            assertTrue(evaluated.verdict.allowed, evaluated.verdict.reason)
            router.commit("План утвержден", evaluated.candidate)
            assertEquals(TaskState.DRAFT, resumed.get(session.id)?.state)
            resumedDb.destroy()
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun dataSource(path: String) = SingleConnectionDataSource("jdbc:sqlite:$path", true).also {
        JdbcClient.create(it).sql("PRAGMA foreign_keys = ON").update()
    }
}

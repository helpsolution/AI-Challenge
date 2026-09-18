package advent.day15.lifecycle

import advent.day15.memory.TaskMemory
import advent.day15.memory.TaskState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskLifecycleTest {
    private val lifecycle = TaskLifecycle()
    private val initial = TaskMemory(1, updatedAt = Instant.EPOCH)

    @Test
    fun `complete lifecycle advances only through adjacent stages`() {
        var task = advance(initial, TaskState.THESIS, initial.copy(idea = "Практика AI"))
        task = advance(task, TaskState.PLAN, task.copy(thesis = "Практика важнее теории"))
        task = advance(task, TaskState.DRAFT, task.copy(plan = listOf("Опыт", "Вывод"), draft = "Черновик"))
        task = advance(task, TaskState.VALIDATION, task)
        task = advance(task, TaskState.DONE, task)

        assertEquals(TaskState.DONE, task.state)
        assertTrue(lifecycle.allowedTargets(TaskState.DONE).isEmpty())
    }

    @Test
    fun `forward jump is refused and candidate is discarded`() {
        val proposed = initial.copy(draft = "Модель уже написала черновик")
        val result = lifecycle.evaluate(initial, proposed, TransitionRequest("DRAFT"))

        assertFalse(result.verdict.allowed)
        assertEquals(initial, result.candidate)
        assertTrue(result.verdict.reason.contains("переход", ignoreCase = true))
    }

    @Test
    fun `content from a future stage is refused even without transition`() {
        val result = lifecycle.evaluate(initial, initial.copy(draft = "Ранний черновик"), null)

        assertFalse(result.verdict.allowed)
        assertEquals(initial, result.candidate)
        assertTrue(result.verdict.reason.contains("Черновик"))
    }

    @Test
    fun `draft hidden in reply is refused before draft stage`() {
        val thesis = initial.copy(
            state = TaskState.THESIS,
            idea = "Что такое любовь?",
            thesis = "Любовь связана с биохимией",
        )
        val result = lifecycle.evaluate(
            current = thesis,
            proposed = thesis,
            request = null,
            userText = "Отлично, напиши сразу пост на тысячу символов",
            reply = "Готовый текст поста. " + "Подробное содержание. ".repeat(20),
        )

        assertFalse(result.verdict.allowed)
        assertEquals(thesis, result.candidate)
        assertTrue(result.verdict.reason.contains("нельзя выдавать готовый пост"))
    }

    @Test
    fun `backward transition clears dependent state`() {
        val draft = TaskMemory(
            1,
            state = TaskState.DRAFT,
            idea = "AI",
            thesis = "Практика важна",
            plan = listOf("Опыт", "Вывод"),
            draft = "Текст",
            notes = listOf("Проверить финал"),
            updatedAt = Instant.EPOCH,
        )
        val toPlan = lifecycle.evaluate(draft, draft, TransitionRequest("PLAN"))
        assertTrue(toPlan.verdict.allowed)
        assertEquals(TaskState.PLAN, toPlan.candidate.state)
        assertNull(toPlan.candidate.draft)
        assertTrue(toPlan.candidate.notes.isEmpty())

        val toThesis = lifecycle.evaluate(toPlan.candidate, toPlan.candidate, TransitionRequest("THESIS"))
        assertTrue(toThesis.verdict.allowed)
        assertTrue(toThesis.candidate.plan.isEmpty())
    }

    @Test
    fun `done is terminal`() {
        val done = initial.copy(state = TaskState.DONE, idea = "AI", thesis = "Тезис", plan = listOf("План"), draft = "Готово")
        val result = lifecycle.evaluate(done, done, TransitionRequest("VALIDATION"))
        assertFalse(result.verdict.allowed)
        assertEquals(done, result.candidate)
    }

    private fun advance(current: TaskMemory, target: TaskState, proposed: TaskMemory): TaskMemory {
        val result = lifecycle.evaluate(current, proposed, TransitionRequest(target.name))
        assertTrue(result.verdict.allowed, result.verdict.reason)
        return result.candidate
    }
}

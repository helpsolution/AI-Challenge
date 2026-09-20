package advent.cookingstate.agent

import advent.cookingstate.config.CookingProperties
import advent.cookingstate.llm.DecisionClient
import advent.cookingstate.llm.LlmException
import advent.cookingstate.store.SessionStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CookingAgentTest {
    @Test
    fun `moves from gathering to choosing and remembers context`() {
        val store = MemoryStore()
        val decisions = listOf(
            AgentDecision(CookingState.GATHERING, listOf("яйца", "сыр"), null, null, emptyList(), emptyList(), "Сколько есть времени?"),
            AgentDecision(CookingState.CHOOSING, listOf("яйца", "сыр"), 15, 1, emptyList(),
                listOf(DishSuggestion("Омлет", "Быстро"), DishSuggestion("Яичница", "Просто")), "Выберите блюдо"),
        ).iterator()
        val agent = CookingAgent(CookingProperties(), store, DecisionClient { _, _ -> decisions.next() })
        val session = agent.create()

        val first = agent.message(session.id, "Есть яйца и сыр")
        val second = agent.message(session.id, "15 минут, одна порция")

        assertEquals(CookingState.GATHERING, first.state)
        assertEquals(CookingState.CHOOSING, second.state)
        assertEquals(2, second.version)
        assertEquals(listOf("яйца", "сыр"), agent.get(session.id).context.ingredients)
        assertEquals(2, second.context.suggestions.size)
    }

    @Test
    fun `refuses unsupported transition without losing saved state`() {
        val store = MemoryStore()
        val agent = CookingAgent(CookingProperties(), store, DecisionClient { _, _ ->
            AgentDecision(CookingState.CHOOSING, listOf("яйца"), null, null, emptyList(),
                listOf(DishSuggestion("Омлет", "Быстро"), DishSuggestion("Яичница", "Просто")), "Выбирайте")
        })
        val session = agent.create()

        assertThrows(LlmException::class.java) { agent.message(session.id, "Есть яйца") }
        assertEquals(CookingState.GATHERING, agent.get(session.id).state)
        assertEquals(0, agent.get(session.id).version)
    }

    @Test
    fun `selects recipe answers question and advances each cooking step`() {
        val store = MemoryStore()
        val options = listOf(DishSuggestion("Омлет", "Есть яйца и сыр"), DishSuggestion("Яичница", "Быстро"))
        val recipe = Recipe("Омлет", listOf("Взбей яйца.", "Приготовь на сковороде."))
        val decisions = listOf(
            AgentDecision(CookingState.CHOOSING, listOf("яйца", "сыр"), 15, 1, emptyList(), options, "Выберите блюдо"),
            AgentDecision(CookingState.READY, listOf("яйца", "сыр"), 15, 1, emptyList(), options, "Рецепт готов. Начинаем?", recipe),
            AgentDecision(CookingState.COOKING, listOf("яйца", "сыр"), 15, 1, emptyList(), options, "Начнём"),
            AgentDecision(CookingState.COOKING, listOf("яйца", "сыр"), 15, 1, emptyList(), options, "Взбей венчиком"),
            AgentDecision(CookingState.COOKING, listOf("яйца", "сыр"), 15, 1, emptyList(), options, "Дальше", advanceStep = true),
            AgentDecision(CookingState.DONE, listOf("яйца", "сыр"), 15, 1, emptyList(), options, "Готово", advanceStep = true),
        ).iterator()
        val agent = CookingAgent(CookingProperties(), store, DecisionClient { _, _ -> decisions.next() })
        val id = agent.create().id

        assertEquals(CookingState.CHOOSING, agent.message(id, "Есть яйца, сыр и 15 минут").state)
        assertEquals(CookingState.READY, agent.message(id, "Выбираю омлет").state)
        val started = agent.message(id, "Начинаем")
        assertEquals(CookingState.COOKING, started.state)
        assertEquals(0, started.context.currentStepIndex)
        assertEquals(true, started.reply.contains("Шаг 1 из 2"))
        assertEquals(0, agent.message(id, "Чем взбить?").context.currentStepIndex)
        val second = agent.message(id, "Готово")
        assertEquals(1, second.context.currentStepIndex)
        assertEquals(true, second.reply.contains("Шаг 2 из 2"))
        val done = agent.message(id, "Сделал")
        assertEquals(CookingState.DONE, done.state)
        assertEquals(2, done.context.currentStepIndex)
        assertEquals(recipe, done.context.recipe)
    }

    @Test
    fun `cannot finish before the last step`() {
        val store = MemoryStore()
        val options = listOf(DishSuggestion("Омлет", "Есть яйца"), DishSuggestion("Яичница", "Быстро"))
        val recipe = Recipe("Омлет", listOf("Первый шаг", "Второй шаг"))
        val initial = store.create()
        store.save(0, initial.copy(state = CookingState.COOKING,
            context = CookingContext(listOf("яйца"), 15, suggestions = options, recipe = recipe, currentStepIndex = 0),
            version = 1))
        val agent = CookingAgent(CookingProperties(), store, DecisionClient { _, _ ->
            AgentDecision(CookingState.DONE, listOf("яйца"), 15, null, emptyList(), options, "Готово", advanceStep = true)
        })

        assertThrows(LlmException::class.java) { agent.message(initial.id, "Готово") }
        assertEquals(CookingState.COOKING, agent.get(initial.id).state)
        assertEquals(0, agent.get(initial.id).context.currentStepIndex)
    }

    private class MemoryStore : SessionStore {
        private val sessions = mutableMapOf<String, CookingSession>()
        override fun create(): CookingSession = CookingSession(
            "test", CookingState.GATHERING, CookingContext(), "Начнём", 0, java.time.Instant.now(),
        ).also { sessions[it.id] = it }
        override fun find(id: String): CookingSession? = sessions[id]
        override fun save(previousVersion: Int, session: CookingSession): Boolean {
            if (sessions[session.id]?.version != previousVersion) return false
            sessions[session.id] = session
            return true
        }
    }
}

package advent.day3

import advent.day3.reasoning.Technique
import advent.day3.reasoning.TechniquePrompts
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TechniquePromptsTest {
    private val prompts = TechniquePrompts()

    @Test
    fun `у прямого ответа нет системной инструкции вовсе`() {
        assertNull(prompts.systemPrompt(Technique.DIRECT, null))
    }

    @Test
    fun `общая добавка — единственное, что получает прямой ответ`() {
        val prompt = prompts.systemPrompt(Technique.DIRECT, "ОТВЕТ:")

        assertEquals(prompts.answerLine("ОТВЕТ:"), prompt)
        assertTrue(prompt!!.contains("ОТВЕТ:"))
    }

    @Test
    fun `пошаговый способ требует шагов и проверки, а не только маркера`() {
        val prompt = prompts.systemPrompt(Technique.STEP_BY_STEP, "ОТВЕТ:")!!

        assertTrue(prompt.contains("пошагово"))
        assertTrue(prompt.contains("проверь"))
        assertTrue(prompt.contains("ОТВЕТ:"))
    }

    @Test
    fun `группа экспертов задаёт три роли и итог`() {
        val prompt = prompts.systemPrompt(Technique.EXPERT_PANEL, null)!!

        listOf("Аналитик", "Инженер", "Критик", "Итог").forEach {
            assertTrue(prompt.contains(it), "в промпте нет роли $it")
        }
    }

    @Test
    fun `автор мета-промпта не решает задачу сам`() {
        val prompt = prompts.metaAuthorPrompt()

        assertTrue(prompt.contains("Решать её не нужно"))
        assertTrue(prompt.contains("самодостаточным"))
    }

    @Test
    fun `пустой маркер не добавляет к промпту ничего`() {
        assertNull(prompts.answerLine(null))
        assertNull(prompts.answerLine("   "))
        assertNull(prompts.systemPrompt(Technique.DIRECT, "  "))

        val stepByStep = prompts.systemPrompt(Technique.STEP_BY_STEP, null)!!
        assertFalse(stepByStep.contains("Последней строкой"))
    }

    @Test
    fun `маркер подставляется в инструкцию как есть`() {
        assertTrue(prompts.answerLine("ИТОГ →")!!.contains("«ИТОГ → <ответ>»"))
    }
}

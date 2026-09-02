package advent.day3

import advent.day3.reasoning.Sections
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SectionsTest {

    @Test
    fun `ответ группы экспертов раскладывается по ролям`() {
        val text = """
            ### Аналитик
            Дано: сутки, две стрелки.

            ### Инженер
            За 12 часов минутная обгоняет часовую 11 раз.

            ### Критик
            Ловушка в удвоении: прямых углов 22, а не 24.

            ### Итог
            ОТВЕТ: 44
        """.trimIndent()

        val sections = Sections.split(text)

        assertEquals(listOf("Аналитик", "Инженер", "Критик", "Итог"), sections.map { it.title })
        assertTrue(sections[1].body.contains("11 раз"))
        assertEquals("ОТВЕТ: 44", sections.last().body)
    }

    @Test
    fun `монолитный ответ на разделы не разбивается`() {
        assertTrue(Sections.split("Просто текст без заголовков.\nВторая строка.").isEmpty())
    }

    @Test
    fun `одного заголовка мало, чтобы считать ответ размеченным`() {
        assertTrue(Sections.split("## Решение\nТекст решения.").isEmpty())
    }

    @Test
    fun `текст до первого заголовка не теряет разделы после себя`() {
        val sections = Sections.split("Вступление.\n## Раз\nа\n## Два\nб")
        assertEquals(listOf("Раз", "Два"), sections.map { it.title })
    }
}

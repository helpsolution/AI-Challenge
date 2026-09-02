package advent.day3

import advent.day3.reasoning.AnswerExtractor
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class AnswerExtractorTest {
    private val extractor = AnswerExtractor()

    @Test
    fun `берётся последнее вхождение маркера, а не первое`() {
        val text = """
            Сначала кажется, что ответ: 48.
            Но прямых углов за 12 часов 22, а не 24.
            ОТВЕТ: 44
        """.trimIndent()

        assertEquals("44", extractor.extract(text, "ОТВЕТ:"))
    }

    @Test
    fun `markdown вокруг ответа снимается`() {
        assertEquals("44", extractor.extract("Итого.\n**ОТВЕТ: 44**", "ОТВЕТ:"))
        assertEquals("44 раза", extractor.extract("### ОТВЕТ: *44 раза*.", "ОТВЕТ:"))
    }

    @Test
    fun `кавычки снимаются парой, одиночная закрывающая остаётся в ответе`() {
        assertEquals("44", extractor.extract("ОТВЕТ: «44»", "ОТВЕТ:"))
        assertEquals(
            "1 фрукт из коробки «яблоки и апельсины»",
            extractor.extract("ОТВЕТ: 1 фрукт из коробки «яблоки и апельсины»", "ОТВЕТ:"),
        )
    }

    @Test
    fun `без маркера берётся последняя непустая строка`() {
        val text = "Шаг 1: считаем обгоны.\nШаг 2: их 22 за 12 часов.\nЗначит, 44 раза.\n\n"
        assertEquals("Значит, 44 раза", extractor.extract(text, null))
    }

    @Test
    fun `маркер запрошен, но модель его не поставила — работает запасной путь`() {
        assertEquals("Ответ 44", extractor.extract("Рассуждение.\nОтвет 44", "ИТОГ:"))
    }

    @Test
    fun `пустой ответ не выделяется`() {
        assertNull(extractor.extract("   \n\n  ", "ОТВЕТ:"))
        assertNull(extractor.extract("ОТВЕТ:", "ОТВЕТ:"))
    }

    @Test
    fun `нормализация гасит расхождения, которые расхождениями не являются`() {
        val forms = listOf("44", "44.", "  44  ", "**44**")
        assertEquals(setOf("44"), forms.map(extractor::normalize).toSet())

        assertEquals(extractor.normalize("Ёлка"), extractor.normalize("елка"))
        assertEquals(extractor.normalize("38,5 года"), extractor.normalize("38.5 года"))
    }

    @Test
    fun `единица счёта не делает ответы разными`() {
        val same = listOf("44", "44 раза", "44 раз в сутки", "**44 раза**.")
        assertEquals(setOf("44"), same.map(extractor::normalize).toSet())

        assertEquals(extractor.normalize("38,5 года"), extractor.normalize("38.5"))
    }

    @Test
    fun `хвост, который не является единицей счёта, остаётся частью ответа`() {
        val notSame = listOf(
            "1 фрукт" to "1",
            "12 процентов роста" to "12 процентов падения",
            "5 яблок" to "5 апельсинов",
        )
        notSame.forEach { (a, b) ->
            assertNotEquals(extractor.normalize(a), extractor.normalize(b), "«$a» и «$b» — разные ответы")
        }
    }

    @Test
    fun `противоположные ответы с одним числом не сливаются`() {
        assertNotEquals(extractor.normalize("выросли на 12%"), extractor.normalize("упали на 12%"))
    }

    @Test
    fun `разные ответы остаются разными`() {
        val a = extractor.normalize("44")
        val b = extractor.normalize("48")
        assertEquals(false, a == b)
    }
}

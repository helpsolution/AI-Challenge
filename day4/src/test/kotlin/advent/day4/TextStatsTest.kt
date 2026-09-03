package advent.day4

import advent.day4.temperature.TextStats
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextStatsTest {

    @Test
    fun `слова считаются без учёта регистра и ё`() {
        assertEquals(listOf("елка", "елка", "елки"), TextStats.words("Ёлка, елка — ёлки!"))
    }

    @Test
    fun `дефис и апостроф внутри слова не разрывают его`() {
        assertEquals(listOf("кое-что", "по-русски"), TextStats.words("кое-что по-русски"))
    }

    @Test
    fun `предложения считаются по точкам, восклицанию и вопросу`() {
        val metrics = TextStats.of("Раз. Два! Три? Четыре…")

        assertEquals(4, metrics.sentences)
        assertEquals(4, metrics.words)
        assertEquals(1.0, metrics.avgSentenceWords)
    }

    @Test
    fun `ответ без знаков конца — всё равно одно предложение`() {
        val metrics = TextStats.of("просто три слова")

        assertEquals(1, metrics.sentences)
        assertEquals(3.0, metrics.avgSentenceWords)
    }

    @Test
    fun `пустой текст не роняет деление на ноль`() {
        val metrics = TextStats.of("   ")

        assertEquals(0, metrics.words)
        assertEquals(0, metrics.sentences)
        assertEquals(0.0, metrics.avgSentenceWords)
        assertEquals(0.0, metrics.lexicalVariety)
    }

    @Test
    fun `на коротком тексте разнообразие — обычная доля уникальных слов`() {
        assertEquals(0.75, TextStats.of("кот кот пес мышь").lexicalVariety)
    }

    /**
     * Главное свойство MATTR и причина, по которой взят он, а не TTR: длина текста
     * не должна влиять на метрику, иначе длинный ответ горячей температуры выглядел бы
     * беднее холодного просто потому, что он длиннее.
     */
    @Test
    fun `разнообразие лексики не падает от одного лишь увеличения длины`() {
        val vocabulary = (1..80).map { "слово$it" }
        val short = vocabulary.take(60).joinToString(" ")
        val long = (1..6).joinToString(" ") { vocabulary.joinToString(" ") }

        val shortVariety = TextStats.of(short).lexicalVariety
        val longVariety = TextStats.of(long).lexicalVariety

        assertEquals(1.0, shortVariety, "в тексте из разных слов повторов нет")
        assertTrue(
            longVariety > 0.9,
            "текст в восемь раз длиннее с тем же словарём не должен терять разнообразие: $longVariety",
        )
    }

    @Test
    fun `повторение одного слова даёт минимальное разнообразие`() {
        val variety = TextStats.of((1..200).joinToString(" ") { "вода" }).lexicalVariety

        assertEquals(0.02, variety, "одно слово в окне из 50 — это 1/50")
    }
}

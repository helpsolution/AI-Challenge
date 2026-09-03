package advent.day4

import advent.day4.temperature.Similarity
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimilarityTest {

    @Test
    fun `дословно совпавшие ответы дают ноль`() {
        assertEquals(0.0, Similarity.distance("Ответ: сорок четыре раза", "Ответ: сорок четыре раза"))
    }

    @Test
    fun `разный регистр и ё расхождением не считаются`() {
        assertEquals(0.0, Similarity.distance("Ёлка в лесу", "елка В ЛЕСУ"))
    }

    @Test
    fun `ответы без общих пар слов дают единицу`() {
        assertEquals(1.0, Similarity.distance("кот сидит на окне", "поезд ушёл в депо"))
    }

    /**
     * Мера считается по парам соседних слов, а не по отдельным словам: у ответов
     * на одну задачу отдельные слова совпадают почти всегда, различает их порядок.
     */
    @Test
    fun `переставленные слова считаются разными ответами`() {
        val distance = Similarity.distance("цена выросла и потом упала", "цена упала и потом выросла")

        assertTrue(distance > 0.5, "порядок слов должен различаться, получилось $distance")
    }

    @Test
    fun `частичное совпадение даёт значение между нулём и единицей`() {
        val distance = Similarity.distance(
            "итоговая цена товара 2208 рублей",
            "итоговая цена товара 2200 рублей",
        )

        assertTrue(distance > 0.0 && distance < 1.0, "ожидалось частичное совпадение, получилось $distance")
    }

    @Test
    fun `пустые тексты не ломают меру`() {
        assertEquals(0.0, Similarity.distance("", ""))
        assertEquals(1.0, Similarity.distance("", "хоть что-то"))
    }

    @Test
    fun `разброс по одному ответу не измеряется`() {
        assertNull(Similarity.spread(listOf("единственный ответ")))
        assertNull(Similarity.spread(emptyList()))
    }

    @Test
    fun `разброс по набору одинаковых ответов равен нулю`() {
        assertEquals(0.0, Similarity.spread(List(3) { "один и тот же ответ" }))
    }

    @Test
    fun `разброс между наборами считается по всем парам`() {
        val spread = Similarity.spreadBetween(
            listOf("один и тот же ответ", "один и тот же ответ"),
            listOf("один и тот же ответ"),
        )

        assertEquals(0.0, spread)
        assertNull(Similarity.spreadBetween(emptyList(), listOf("ответ")))
    }
}

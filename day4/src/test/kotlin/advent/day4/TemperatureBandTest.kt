package advent.day4

import advent.day4.temperature.TemperatureBand
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TemperatureBandTest {

    @Test
    fun `три температуры из задания попадают в три разных диапазона`() {
        assertEquals(TemperatureBand.COLD, TemperatureBand.of(0.0))
        assertEquals(TemperatureBand.WARM, TemperatureBand.of(0.7))
        assertEquals(TemperatureBand.HOT, TemperatureBand.of(1.2))
    }

    @Test
    fun `границы диапазонов включаются в нижний из двух`() {
        assertEquals(TemperatureBand.COLD, TemperatureBand.of(0.3))
        assertEquals(TemperatureBand.WARM, TemperatureBand.of(0.31))
        assertEquals(TemperatureBand.WARM, TemperatureBand.of(0.9))
        assertEquals(TemperatureBand.HOT, TemperatureBand.of(0.91))
        assertEquals(TemperatureBand.HOT, TemperatureBand.of(1.5))
        assertEquals(TemperatureBand.SCALDING, TemperatureBand.of(1.6))
    }

    @Test
    fun `максимум провайдера попадает в перегрев`() {
        assertEquals(TemperatureBand.SCALDING, TemperatureBand.of(2.0))
    }

    @Test
    fun `у каждого диапазона есть рекомендации в обе стороны`() {
        TemperatureBand.entries.forEach { band ->
            assertTrue(band.bestFor.isNotEmpty(), "${band.name}: нечего рекомендовать")
            assertTrue(band.avoidFor.isNotEmpty(), "${band.name}: не указано, где диапазон вредит")
            assertTrue(band.emoji.isNotBlank(), "${band.name}: нет пометки для интерфейса")
        }
    }
}

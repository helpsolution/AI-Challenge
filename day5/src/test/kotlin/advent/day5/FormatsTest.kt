package advent.day5

import advent.day5.compare.Formats
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Суммы в этом дне отличаются на пять порядков: доли цента у слабой модели и заметные
 * центы у сильной. Единый формат на всех либо съедает значащие цифры у первой,
 * либо превращает вторую в частокол нулей.
 */
class FormatsTest {

    @Test
    fun `у любой суммы остаются значащие цифры, а не нули`() {
        assertEquals("$1.25", Formats.money(1.2543))
        assertEquals("$0.0342", Formats.money(0.03421))
        assertEquals("$0.000418", Formats.money(0.00041823))
        assertEquals("$0.00000412", Formats.money(0.0000041234))
    }

    @Test
    fun `хвостовые нули не показываются`() {
        assertEquals("$0.5", Formats.money(0.5))
        assertEquals("$0.001", Formats.money(0.001))
    }

    @Test
    fun `бесплатный запрос — это ноль, а не округлённая мелочь`() {
        assertEquals("$0", Formats.money(0.0))
        assertEquals("$0", Formats.money(-1.0))
    }

    @Test
    fun `кратность крупнее десяти пишется без дробной части`() {
        assertEquals("1.4×", Formats.ratio(1.42))
        assertEquals("9.9×", Formats.ratio(9.94))
        assertEquals("75×", Formats.ratio(74.6))
    }

    @Test
    fun `округление не съедает мелкие суммы, потому что к ним не применяется`() {
        assertEquals(1.43, Formats.round(1.4321))
        assertEquals(12.3, Formats.round(12.34, digits = 1))
    }
}

package advent.day5.compare

import java.util.Locale

/**
 * Форматирование чисел для текстового вывода. Вынесено отдельно, потому что
 * суммы в этом дне отличаются на пять порядков: доли цента у слабой модели
 * и заметные центы у сильной. Один формат на всех либо съедает значащие цифры
 * у первой, либо превращает вторую в частокол нулей.
 */
object Formats {

    /** Доллары: столько знаков после запятой, чтобы осталось две значащие цифры. */
    fun money(amount: Double): String {
        if (amount <= 0) return "$0"
        val decimals = when {
            amount >= 1 -> 2
            amount >= 0.01 -> 4
            amount >= 0.0001 -> 6
            else -> 8
        }
        return "$" + String.format(Locale.US, "%.${decimals}f", amount).trimEnd('0').trimEnd('.')
    }

    /** Кратность: «в 12×», «в 1.4×». Ниже двух десятых доля перестаёт быть кратностью. */
    fun ratio(value: Double): String = when {
        value >= 10 -> String.format(Locale.US, "%.0f×", value)
        else -> String.format(Locale.US, "%.1f×", value)
    }

    fun seconds(millis: Long): String = String.format(Locale.US, "%.1f с", millis / 1000.0)

    fun decimal(value: Double, digits: Int = 1): String = String.format(Locale.US, "%.${digits}f", value)

    /** Округление до двух знаков — для чисел, которые уезжают в JSON, а не в текст. */
    fun round(value: Double, digits: Int = 2): Double {
        val factor = Math.pow(10.0, digits.toDouble())
        return Math.round(value * factor) / factor
    }
}

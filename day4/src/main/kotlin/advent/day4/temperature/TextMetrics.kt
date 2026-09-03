package advent.day4.temperature

/**
 * Числовые признаки одного ответа. Это не оценка качества: считается то,
 * что считается объективно, а вывод «этот ответ лучше» остаётся за человеком.
 */
data class TextMetrics(
    val chars: Int,
    val words: Int,
    val sentences: Int,
    /** Средняя длина предложения в словах. */
    val avgSentenceWords: Double,
    /**
     * Доля неповторяющихся слов, посчитанная по окну (MATTR).
     * Косвенный признак «богатства» словаря — см. [TextStats].
     */
    val lexicalVariety: Double,
)

/**
 * Разбор текста на слова и предложения.
 *
 * Богатство словаря считается не как «уникальных слов / всего слов» (TTR): та доля
 * механически падает с длиной текста — в длинном ответе служебные слова повторяются
 * неизбежно. Сравнивать по ней ответы разной длины нельзя, а при росте температуры
 * длина как раз меняется.
 *
 * Поэтому берётся MATTR: TTR считается в скользящем окне фиксированного размера
 * и усредняется по всем окнам. Длина текста на результат больше не влияет,
 * сравнение между температурами становится честным.
 */
object TextStats {

    /** Окно MATTR. 50 слов — общепринятый компромисс: устойчиво, но применимо к коротким ответам. */
    private const val WINDOW = 50

    private val WORD = Regex("""[\p{L}\p{Nd}]+(?:[-'][\p{L}\p{Nd}]+)*""")

    /** Конец предложения: точка, восклицательный или вопросительный знак, многоточие. */
    private val SENTENCE_END = Regex("""[.!?…]+""")

    fun of(text: String): TextMetrics {
        val words = words(text)
        val sentences = sentenceCount(text, words.size)
        return TextMetrics(
            chars = text.length,
            words = words.size,
            sentences = sentences,
            avgSentenceWords = if (sentences == 0) 0.0 else round2(words.size.toDouble() / sentences),
            lexicalVariety = round2(movingAverageTtr(words)),
        )
    }

    /** Слова в нижнем регистре: «Ёлка» и «елка» — одно слово, дефис и апостроф внутри слова сохраняются. */
    fun words(text: String): List<String> =
        WORD.findAll(text.lowercase().replace('ё', 'е')).map { it.value }.toList()

    /**
     * MATTR: среднее TTR по всем окнам длиной [WINDOW].
     * Текст короче окна считается одним окном — тогда это обычный TTR,
     * и сравнивать такие ответы с длинными уже нельзя (о чём говорит интерфейс).
     */
    private fun movingAverageTtr(words: List<String>): Double {
        if (words.isEmpty()) return 0.0
        if (words.size <= WINDOW) return words.distinct().size.toDouble() / words.size

        var sum = 0.0
        for (start in 0..words.size - WINDOW) {
            sum += words.subList(start, start + WINDOW).distinct().size.toDouble() / WINDOW
        }
        return sum / (words.size - WINDOW + 1)
    }

    /**
     * Предложений в тексте. Ответ без единого знака конца — всё равно одно предложение,
     * иначе средняя длина делилась бы на ноль.
     */
    private fun sentenceCount(text: String, words: Int): Int {
        if (words == 0) return 0
        return SENTENCE_END.findAll(text).count().coerceAtLeast(1)
    }

    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0
}

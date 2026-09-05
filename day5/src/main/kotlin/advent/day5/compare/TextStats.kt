package advent.day5.compare

/**
 * Числовые признаки ответа. Это не оценка качества: считается только то, что считается
 * объективно. Длина сама по себе ничего не говорит о том, хорош ли ответ, — но рядом
 * с числом выходных токенов она показывает, за что именно заплачено.
 */
data class TextMetrics(
    val chars: Int,
    val words: Int,
    val sentences: Int,
)

object TextStats {
    private val WORD = Regex("""[\p{L}\p{Nd}]+(?:[-'][\p{L}\p{Nd}]+)*""")
    private val SENTENCE_END = Regex("""[.!?…]+""")

    fun of(text: String): TextMetrics {
        val words = WORD.findAll(text).count()
        return TextMetrics(
            chars = text.length,
            words = words,
            // Ответ без единого знака конца — всё равно одно предложение.
            sentences = if (words == 0) 0 else SENTENCE_END.findAll(text).count().coerceAtLeast(1),
        )
    }
}

package advent.day4.temperature

import org.springframework.stereotype.Component

/**
 * Достаёт из развёрнутого решения ту одну строку, которую можно сопоставить с ответами других прогонов.
 *
 * Переехал из дня 3 без изменений. Там он сводил к сопоставимому виду ответы разных
 * способов рассуждения, здесь — ответы одной задачи при разной температуре: на задаче
 * с одним правильным ответом три финальные строки рядом и показывают, где точность поплыла.
 */
@Component
class AnswerExtractor {

    /**
     * Ответ после последнего вхождения [marker] и до конца строки. Маркер ищется с конца:
     * модель нередко упоминает слово «ответ» по ходу рассуждения, но итог ставит последним.
     *
     * Если маркера нет (или он не запрашивался) — берётся последняя непустая строка.
     * Это грубее, но лучше, чем ничего: у короткого ответа итог обычно и есть последняя строка.
     */
    fun extract(text: String, marker: String?): String? {
        val byMarker = marker?.trim()?.takeIf { it.isNotEmpty() }?.let { m ->
            val at = text.lastIndexOf(m, ignoreCase = true)
            if (at < 0) null else text.substring(at + m.length).lineSequence().firstOrNull()
        }
        val raw = byMarker ?: text.lineSequence().lastOrNull { it.isNotBlank() }
        return raw?.let(::cleanup)?.takeIf { it.isNotEmpty() }?.take(MAX_LENGTH)
    }

    /**
     * Приводит ответ к виду, в котором его можно сравнивать: регистр, разметка,
     * знаки препинания и запятая в дробях не должны считаться расхождением.
     *
     * Отдельно снимается единица счёта: «44» и «44 раза» — один и тот же ответ,
     * и разными их считает только машина.
     *
     * Хвост отбрасывается, только если он целиком состоит из слов-единиц. Правила
     * «не больше пары слов» тут мало: «12 процентов роста» и «12 процентов падения» —
     * ответы противоположные, а по длине неотличимые.
     */
    fun normalize(answer: String): String = countedNumber(plain(answer))

    private fun countedNumber(normalized: String): String {
        val match = LEADING_NUMBER.matchEntire(normalized) ?: return normalized
        val (number, tail) = match.destructured
        val words = tail.split(' ').filter { it.isNotBlank() }
        return if (words.all { it in COUNT_WORDS }) number else normalized
    }

    private fun plain(answer: String): String = answer
        .lowercase()
        .replace('ё', 'е')
        .replace(DECIMAL_COMMA) { "${it.groupValues[1]}.${it.groupValues[2]}" }
        .replace(NOISE, " ")
        .replace(SPACES, " ")
        .trim()
        // Точка в конце — пунктуация, точка внутри — дробь: обрезается только хвост.
        .trimEnd('.')
        .trim()

    /**
     * Снимает с найденной строки markdown-обрамление и остатки пунктуации.
     * По кругу, пока строка меняется: «*44 раза*.» открывается только послойно —
     * сначала точка, потом звёздочка под ней.
     */
    private fun cleanup(line: String): String {
        var current = line.trim().replace(BULLET, "")
        while (true) {
            val next = unquote(current)
                .trim(*WRAPPERS)
                .trimEnd('.', ',', ';')
                .trim()
            if (next == current) return next
            current = next
        }
    }

    /**
     * Кавычки снимаются только парой. Одиночная закрывающая — часть ответа:
     * в «1 фрукт из коробки «яблоки и апельсины»» она закрывает содержимое, а не ответ.
     */
    private fun unquote(text: String): String {
        if (text.length < 2) return text
        val pair = QUOTES[text.first()]
        return if (pair == text.last()) text.substring(1, text.length - 1).trim() else text
    }

    private companion object {
        const val MAX_LENGTH = 300

        /** Парные кавычки: открывающая → закрывающая. */
        val QUOTES = mapOf('«' to '»', '"' to '"', '\u201C' to '\u201D', '\'' to '\'')
        val DECIMAL_COMMA = Regex("""(\d),(\d)""")

        /** Маркер списка в начале строки. Минус, за которым нет пробела, — это знак числа. */
        val BULLET = Regex("""^[-*\u2022]\s+""")

        /** Число в начале ответа и словесный хвост за ним. */
        val LEADING_NUMBER = Regex("""(-?\d+(?:\.\d+)?)((?:\s+\p{L}+)*)""")

        /**
         * Слова, которые ничего не добавляют к числу: единицы счёта и предлоги при них.
         * Всё остальное в хвосте — часть ответа, и ответы с разными хвостами разные.
         */
        val COUNT_WORDS = setOf(
            "раз", "раза", "штук", "штуки", "штука",
            "год", "года", "лет", "месяц", "месяца", "месяцев",
            "день", "дня", "дней", "сутки", "суток",
            "час", "часа", "часов", "минуту", "минуты", "минут", "секунду", "секунды", "секунд",
            "процент", "процента", "процентов",
            "рубль", "рубля", "рублей", "градус", "градуса", "градусов",
            "в", "за", "и",
        )

        /**
         * Обрамление, не несущее смысла. Кавычек здесь нет — они снимаются парой отдельно.
         * Минуса нет тоже: «-5» — валидный ответ.
         */
        val WRAPPERS = charArrayOf('*', '_', '`', '#', ':', '\u2014', '\u2013', ' ')

        /** Всё, что не буква, не цифра, не точка и не минус, схлопывается в пробел. */
        val NOISE = Regex("""[^\p{L}\p{Nd}.\-]+""")
        val SPACES = Regex("""\s+""")
    }
}

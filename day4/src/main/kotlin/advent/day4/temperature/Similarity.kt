package advent.day4.temperature

/**
 * Насколько два ответа непохожи друг на друга.
 *
 * Считается по парам соседних слов (биграммам), а не по отдельным словам: одиночные
 * слова у ответов на одну задачу совпадают почти всегда — тема-то общая. Различает
 * ответы именно то, как слова составлены во фразы, и биграммы это ловят.
 *
 * Мера — расстояние Жаккара: доля биграмм, встретившихся только в одном из двух ответов.
 * 0 — тексты дословно совпадают, 1 — общих пар слов нет вовсе.
 *
 * Это мера различия текстов, а не смысла: два верных ответа, сказанных разными словами,
 * дадут большое расстояние. Поэтому число сравнивается с другими такими же числами
 * (0 против 1.2), а не толкуется само по себе.
 */
object Similarity {

    fun distance(first: String, second: String): Double {
        val a = shingles(first)
        val b = shingles(second)
        if (a.isEmpty() && b.isEmpty()) return 0.0
        if (a.isEmpty() || b.isEmpty()) return 1.0

        val intersection = a.count { it in b }
        val union = a.size + b.size - intersection
        return round3(1.0 - intersection.toDouble() / union)
    }

    /**
     * Средний разброс внутри набора ответов: среднее расстояние по всем парам.
     * Меньше двух текстов — сравнивать не с чем, возвращается null.
     */
    fun spread(texts: List<String>): Double? {
        if (texts.size < 2) return null
        val distances = texts.indices.flatMap { i ->
            (i + 1 until texts.size).map { j -> distance(texts[i], texts[j]) }
        }
        return round3(distances.average())
    }

    /** Средний разброс между двумя наборами: каждый ответ одного сравнивается с каждым ответом другого. */
    fun spreadBetween(first: List<String>, second: List<String>): Double? {
        if (first.isEmpty() || second.isEmpty()) return null
        return round3(first.flatMap { a -> second.map { b -> distance(a, b) } }.average())
    }

    private fun shingles(text: String): Set<String> {
        val words = TextStats.words(text)
        if (words.size < 2) return words.toSet()
        return words.zipWithNext { a, b -> "$a $b" }.toSet()
    }

    private fun round3(value: Double): Double = Math.round(value * 1000.0) / 1000.0
}

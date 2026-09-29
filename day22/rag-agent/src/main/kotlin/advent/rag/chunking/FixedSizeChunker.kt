package advent.rag.chunking

/**
 * Стратегия «фиксированный размер» из day21. Окно длиной [chunkSize] символов шагает по тексту
 * с шагом `chunkSize - overlap`: конец каждого чанка повторяется в начале следующего.
 *
 * Режет вслепую — посреди предложения и даже слова. Перекрытие нужно, чтобы мысль со стыка
 * целиком попала хотя бы в один из соседних чанков.
 */
class FixedSizeChunker(private val chunkSize: Int, private val overlap: Int) {
    init {
        // Иначе окно не сдвигается вперёд и цикл не кончается.
        require(chunkSize > 0) { "chunkSize должен быть больше 0, пришло $chunkSize" }
        require(overlap in 0 until chunkSize) { "overlap должен быть от 0 до chunkSize - 1, пришло $overlap" }
    }

    fun split(document: Document): List<FixedSizeChunk> =
        windows(document.text).mapIndexed { i, (start, end) ->
            FixedSizeChunk(
                chunkId = document.chunkId(i),
                source = document.source,
                title = document.title,
                url = document.url,
                index = i,
                start = start,
                end = end,
                text = document.text.substring(start, end),
            )
        }

    /** Границы окон в тексте: пары (start, end). */
    private fun windows(text: String): List<Pair<Int, Int>> {
        val step = chunkSize - overlap
        val windows = mutableListOf<Pair<Int, Int>>()
        var start = 0
        while (true) {
            val end = text.boundaryAt(minOf(start + chunkSize, text.length))
            // Кусок из одних пробелов и переводов строк искать нечего, а эмбеддинг у пустоты не посчитать.
            if (text.substring(start, end).isNotBlank()) windows += start to end
            if (end == text.length) return windows
            start = text.boundaryAt(start + step)
        }
    }

    /**
     * Эмодзи и редкие иероглифы в UTF-16 занимают два char. Разрезать такую пару нельзя: половинка
     * не кодируется ни в JSON, ни для модели. Если граница попала внутрь пары, сдвигаем её за пару.
     */
    private fun String.boundaryAt(i: Int): Int =
        if (i in 1 until length && this[i - 1].isHighSurrogate() && this[i].isLowSurrogate()) i + 1 else i
}

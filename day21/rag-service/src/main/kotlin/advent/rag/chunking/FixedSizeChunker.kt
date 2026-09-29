package advent.rag.chunking

import advent.rag.document.Document

/**
 * Стратегия «фиксированный размер». Окно длиной [chunkSize] символов шагает по тексту
 * с шагом `chunkSize - overlap`: конец каждого чанка повторяется в начале следующего.
 *
 * Размер — в символах, а не в токенах: токенизатора модели на JVM нет, а символы видно глазами.
 * Режет вслепую — посреди предложения и даже слова. Это и есть цена стратегии: мысль на стыке
 * разрывается, и перекрытие нужно, чтобы она целиком попала хотя бы в один из соседних чанков.
 */
class FixedSizeChunker(
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    private val overlap: Int = DEFAULT_OVERLAP,
) : Chunker<FixedSizeChunk> {
    init {
        // Иначе окно не сдвигается вперёд и цикл не кончается.
        require(chunkSize > 0) { "chunkSize должен быть больше 0, пришло $chunkSize" }
        require(overlap in 0 until chunkSize) { "overlap должен быть от 0 до chunkSize - 1, пришло $overlap" }
    }

    override fun split(document: Document): List<FixedSizeChunk> =
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

    /** Границы окон в тексте: пары (start, end). Им же пользуется структурная стратегия как последним резервом. */
    internal fun windows(text: String): List<Pair<Int, Int>> {
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

    companion object {
        /** 500 и 50 — числа из лекции. Там они в токенах, здесь в символах. */
        const val DEFAULT_CHUNK_SIZE = 500
        const val DEFAULT_OVERLAP = 50
    }
}

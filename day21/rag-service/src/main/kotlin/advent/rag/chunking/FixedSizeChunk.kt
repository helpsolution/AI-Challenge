package advent.rag.chunking

/** Чанк фиксированного размера. [start] и [end] — границы в тексте документа: `text == document.text.substring(start, end)`. */
data class FixedSizeChunk(
    val chunkId: String,
    val source: String,
    val title: String,
    val url: String?,
    /** Порядковый номер чанка в документе, с нуля. */
    val index: Int,
    val start: Int,
    val end: Int,
    val text: String,
)

package advent.rag.chunking

/** Чанк по структуре. [start] и [end] — границы в тексте документа: `text == document.text.substring(start, end)`. */
data class StructuralChunk(
    val chunkId: String,
    val source: String,
    val title: String,
    val url: String?,
    /** Цепочка заголовков над чанком: «Кубики › Чанкинг». null — текст до первого заголовка. */
    val section: String?,
    /** Порядковый номер чанка в документе, с нуля. */
    val index: Int,
    val start: Int,
    val end: Int,
    val text: String,
)

package advent.rag.web

/** Ответ эндпоинтов /api/chunking/…: чанки своей стратегии и их число. */
data class ChunkingResponse<T>(
    val count: Int,
    val chunks: List<T>,
) {
    constructor(chunks: List<T>) : this(chunks.size, chunks)
}

package advent.rag.chunking

data class Chunk(
    val chunkId: String,
    val source: String,
    val title: String,
    val url: String?,
    val section: String?,
    val contextPrefix: String = "",
    val index: Int,
    // Offsets относятся к text документа без YAML, а не к content с повторёнными шапками.
    val start: Int,
    val end: Int,
    val text: String,
    val contextSuffix: String = "",
) {
    val content: String get() = contextPrefix + text + contextSuffix
    val embeddingText: String get() = listOfNotNull(title, section, content).joinToString("\n\n")
}

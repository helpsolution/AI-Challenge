package advent.review.chunking

data class Document(
    val source: String,
    val title: String,
    val url: String?,
    val text: String,
) {
    fun chunkId(index: Int) = "$source#$index"
}

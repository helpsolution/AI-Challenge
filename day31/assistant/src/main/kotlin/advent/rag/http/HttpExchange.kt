package advent.rag.http

data class HttpExchange(
    val method: String,
    val url: String,
    val requestHeaders: Map<String, String>,
    val requestBody: String,
    val status: Int,
    val responseBody: String,
    val durationMs: Long,
)

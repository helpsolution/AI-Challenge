package advent.rag.http

/**
 * Один HTTP-обмен с внешним сервисом — ровно то, что ушло и что пришло. Нужен странице «Внутри агента»:
 * видно настоящий запрос к модели, а не пересказ. Секреты в [requestHeaders] уже замаскированы.
 */
data class HttpExchange(
    val method: String,
    val url: String,
    val requestHeaders: Map<String, String>,
    val requestBody: String,
    val status: Int,
    val responseBody: String,
    val durationMs: Long,
)

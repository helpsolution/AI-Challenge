package advent.day2.llm

/**
 * Полный протокол одного обращения к провайдеру: что именно ушло по сети и что вернулось.
 * Нужен интерфейсу, чтобы показать пользователю настоящий HTTP-обмен, а не его пересказ.
 * Ключ в заголовках всегда замаскирован.
 */
data class LlmExchange(
    val url: String,
    val method: String,
    val requestHeaders: Map<String, String>,
    val requestBody: String,
    val status: Int?,
    val responseBody: String?,
    val parsed: ChatCompletionResponse?,
)

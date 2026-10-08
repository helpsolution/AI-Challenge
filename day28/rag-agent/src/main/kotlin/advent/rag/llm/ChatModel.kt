package advent.rag.llm

import advent.rag.http.HttpExchange
import com.fasterxml.jackson.annotation.JsonValue
import tools.jackson.databind.JsonNode

data class ChatMessage(val role: String, val content: String)

enum class Provider(@get:JsonValue val code: String) {
    LOCAL("local"),
    CLOUD("cloud"),
}

data class Completion(
    val text: String,
    val model: String,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val finishReason: String?,
    val exchange: HttpExchange,
    val timings: Timings?,
)

// Где модель провела время, по отчёту Ollama. У облака таких данных нет — только общее время HTTP-запроса.
data class Timings(val loadMs: Long, val promptMs: Long, val generationMs: Long, val cachedPromptTokens: Int?)

class LlmException(message: String) : RuntimeException(message)

interface ChatModel {
    val provider: Provider
    val model: String

    // schema — JSON-схема ответа. Ollama ограничивает генерацию схемой, DeepSeek умеет только «любой JSON-объект».
    fun complete(messages: List<ChatMessage>, schema: JsonNode, maxTokens: Int, temperature: Double? = null): Completion
}

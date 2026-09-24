package advent.pipeline.bot

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Модель запроса и ответа OpenAI-совместимого chat completions API — на нём говорит DeepSeek.
 * Здесь описан только тот кусок, который нужен агенту: сообщения и вызовы инструментов.
 */
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    val tools: List<ToolDefinition>? = null,
    val temperature: Double? = null,
    /**
     * Потолок ответа модели. Статьи идут по конвейеру через аргументы вызовов, и десяток статей
     * в аргументах habr_summarize — это несколько тысяч токенов: стандартного потолка может не хватить.
     */
    @SerialName("max_tokens") val maxTokens: Int? = null,
)

@Serializable
data class Message(
    /** system, user, assistant или tool. */
    val role: String,
    val content: String? = null,
    /** Заполняется моделью, когда она решила позвать инструменты. */
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    /** Обязателен в ответе инструмента: связывает результат с конкретным вызовом. */
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall,
)

/** Аргументы приходят строкой с JSON внутри — так устроен протокол, это не ошибка. */
@Serializable
data class FunctionCall(
    val name: String,
    val arguments: String,
)

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionSpec,
)

@Serializable
data class FunctionSpec(
    val name: String,
    val description: String,
    /** JSON Schema аргументов — приезжает из MCP как есть. */
    val parameters: JsonObject,
)

@Serializable
data class ChatResponse(val choices: List<Choice>)

@Serializable
data class Choice(
    val message: Message,
    @SerialName("finish_reason") val finishReason: String? = null,
)

class LlmException(message: String) : RuntimeException(message)

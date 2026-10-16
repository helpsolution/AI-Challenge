package advent.files.llm

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.JsonNode

// Сообщения диалога в формате OpenAI-совместимого API DeepSeek: у каждой роли свой набор полей.
sealed interface Message { val role: String }

data class SystemMessage(val content: String) : Message { override val role = "system" }

data class UserMessage(val content: String) : Message { override val role = "user" }

data class AssistantMessage(
    val content: String,
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY) @get:JsonProperty("tool_calls") val toolCalls: List<ToolCall> = emptyList(),
) : Message { override val role = "assistant" }

data class ToolMessage(@get:JsonProperty("tool_call_id") val toolCallId: String, val content: String) : Message {
    override val role = "tool"
}

data class ToolCall(val id: String, val function: FunctionCall) { val type = "function" }

// arguments — JSON-строка, как её написала модель; разбирает и проверяет исполнитель инструмента.
data class FunctionCall(val name: String, val arguments: String)

data class ToolSpec(val function: FunctionSpec) { val type = "function" }

data class FunctionSpec(val name: String, val description: String, val parameters: JsonNode)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    val temperature: Double,
    @JsonProperty("max_tokens") val maxTokens: Int,
    val tools: List<ToolSpec>?,
    @JsonProperty("tool_choice") val toolChoice: String?,
    val stream: Boolean = true,
    @JsonProperty("stream_options") val streamOptions: Map<String, Boolean> = mapOf("include_usage" to true),
    val thinking: Map<String, String> = mapOf("type" to "disabled"),
)

internal data class StreamChunk(val model: String?, val choices: List<StreamChoice> = emptyList(), val usage: Usage?)

internal data class StreamChoice(val delta: Delta?, @JsonProperty("finish_reason") val finishReason: String?)

internal data class Delta(val content: String?, @JsonProperty("tool_calls") val toolCalls: List<ToolCallDelta>?)

internal data class ToolCallDelta(val index: Int, val id: String?, val function: FunctionDelta?)

internal data class FunctionDelta(val name: String?, val arguments: String?)

internal data class Usage(
    @JsonProperty("prompt_tokens") val promptTokens: Int,
    @JsonProperty("completion_tokens") val completionTokens: Int,
)

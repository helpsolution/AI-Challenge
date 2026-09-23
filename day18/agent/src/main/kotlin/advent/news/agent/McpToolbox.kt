package advent.news.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Что вернул инструмент: текст для модели и структура для кода — ровно два поля результата MCP. */
data class ToolOutcome(
    val text: String,
    val structured: JsonObject?,
    val isError: Boolean,
)

/**
 * Второй из двух клиентов агента — к MCP-серверу.
 * Отвечает за три вещи: подключиться, получить список инструментов и вызвать инструмент.
 *
 * Перевод из MCP в формат LLM живёт здесь же: схемы инструментов у MCP и у OpenAI-совместимого
 * API совпадают по смыслу, поэтому переложить одно в другое — это несколько строк, а не адаптер.
 */
class McpToolbox private constructor(
    private val client: Client,
    private val http: HttpClient,
    val tools: List<Tool>,
) {
    val serverInfo: Implementation? get() = client.serverVersion

    /** Описания инструментов в том виде, в каком их ждёт LLM. */
    val definitions: List<ToolDefinition> = tools.map { it.toDefinition() }

    /** Вызов, который попросила модель: аргументы приходят строкой с JSON внутри. */
    suspend fun call(toolCall: ToolCall): ToolOutcome {
        val arguments = parseArguments(toolCall.function.arguments)
            ?: return ToolOutcome(
                text = "Аргументы не разобраны как JSON: ${toolCall.function.arguments}",
                structured = null,
                isError = true,
            )
        return call(toolCall.function.name, arguments)
    }

    /**
     * Вызов из кода, без модели. Так работает сводка по расписанию: когда её делать и за какой период,
     * решает таймер, а не модель, — поэтому инструмент зовётся напрямую, с известными аргументами.
     */
    suspend fun call(name: String, arguments: JsonObject): ToolOutcome {
        val result = try {
            client.callTool(CallToolRequest(CallToolRequestParams(name = name, arguments = arguments)))
        } catch (e: Exception) {
            // Сбой инструмента не должен ронять диалог: отдаём модели причину, она попробует иначе.
            return ToolOutcome(
                text = "Инструмент $name не выполнен: ${e.message ?: e::class.simpleName}",
                structured = null,
                isError = true,
            )
        }

        val text = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
        return ToolOutcome(
            text = text.ifBlank { "Инструмент отработал, но ничего не сказал" },
            structured = result.structuredContent,
            isError = result.isError == true,
        )
    }

    suspend fun close() {
        client.close()
        http.close()
    }

    companion object {
        suspend fun connect(config: AgentConfig): McpToolbox {
            // SSE обязателен: транспорт Streamable HTTP умеет получать ответы потоком событий.
            val http = HttpClient(CIO) {
                install(SSE)
                // Подписка сразу делает первый сбор, а лента может отвечать долго: стандартных 15 секунд CIO мало.
                engine { requestTimeout = REQUEST_TIMEOUT_MS }
            }
            val client = http.mcpStreamableHttp(config.mcpUrl) {
                config.mcpAuthToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }
            return McpToolbox(client, http, client.listTools().tools)
        }

        private const val REQUEST_TIMEOUT_MS = 60_000L

        private val JSON = Json { ignoreUnknownKeys = true }

        private fun parseArguments(raw: String): JsonObject? {
            if (raw.isBlank()) return JsonObject(emptyMap())
            return runCatching { JSON.parseToJsonElement(raw).jsonObject }.getOrNull()
        }

        private fun Tool.toDefinition(): ToolDefinition = ToolDefinition(
            function = FunctionSpec(
                name = name,
                description = description.orEmpty(),
                parameters = buildJsonObject {
                    put("type", "object")
                    inputSchema.properties?.let { put("properties", it) }
                    inputSchema.required?.let { required ->
                        putJsonArray("required") { required.forEach { add(it) } }
                    }
                },
            ),
        )
    }
}

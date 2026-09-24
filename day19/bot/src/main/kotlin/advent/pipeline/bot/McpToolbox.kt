package advent.pipeline.bot

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Что вернул инструмент: текст для модели и структура для кода — ровно два поля результата MCP. */
data class ToolOutcome(
    val text: String,
    val structured: JsonObject?,
    val isError: Boolean,
)

/**
 * Клиент MCP-сервера. Отвечает за три вещи: подключиться, получить список инструментов и вызвать инструмент.
 *
 * Перевод из MCP в формат LLM живёт здесь же: схемы инструментов у MCP и у OpenAI-совместимого
 * API совпадают по смыслу, поэтому переложить одно в другое — это несколько строк, а не адаптер.
 */
class McpToolbox private constructor(
    private val client: Client,
    private val http: HttpClient,
    val tools: List<Tool>,
) {
    /** Описания инструментов в том виде, в каком их ждёт LLM. */
    val definitions: List<ToolDefinition> = tools.map { it.toDefinition() }

    /** Вызов, который попросила модель: аргументы приходят строкой с JSON внутри. */
    suspend fun call(name: String, arguments: JsonObject?): ToolOutcome {
        if (arguments == null) {
            return ToolOutcome(text = "Аргументы не разобраны как JSON-объект", structured = null, isError = true)
        }
        val result = try {
            client.callTool(CallToolRequest(CallToolRequestParams(name = name, arguments = arguments)))
        } catch (e: CancellationException) {
            throw e
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
        suspend fun connect(url: String, authToken: String?): McpToolbox {
            // SSE обязателен: транспорт Streamable HTTP умеет получать ответы потоком событий.
            val http = HttpClient(CIO) {
                install(SSE)
                // habr_summarize ждёт, пока DeepSeek напишет сводку: стандартных 15 секунд CIO мало.
                engine { requestTimeout = REQUEST_TIMEOUT_MS }
            }
            val client = http.mcpStreamableHttp(url) {
                authToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }
            return McpToolbox(client, http, client.listTools().tools)
        }

        private const val REQUEST_TIMEOUT_MS = 180_000L

        private val JSON = Json { ignoreUnknownKeys = true }

        /** Пустая строка — вызов без аргументов; не JSON-объект — null, такой вызов выполнять нельзя. */
        fun parseArguments(raw: String): JsonObject? {
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

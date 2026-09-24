package advent.habr.bot

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
import kotlinx.serialization.json.JsonObject

/** Что вернул инструмент: текст для модели и структура для кода — ровно два поля результата MCP. */
data class ToolOutcome(
    val text: String,
    val structured: JsonObject?,
    val isError: Boolean,
)

/**
 * Клиент MCP-сервера. Бот зовёт инструменты напрямую из кода: какую команду прислал человек,
 * такой инструмент и нужен, выбирать модели тут нечего. Модель подключается только чтобы
 * превратить результат news_digest в текст сводки.
 */
class McpToolbox private constructor(
    private val client: Client,
    private val http: HttpClient,
    val tools: List<Tool>,
) {
    suspend fun call(name: String, arguments: JsonObject = JsonObject(emptyMap())): ToolOutcome {
        val result = try {
            client.callTool(CallToolRequest(CallToolRequestParams(name = name, arguments = arguments)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Сбой инструмента не должен ронять бота: причину увидит человек в ответе.
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
                // Включение сбора сразу скачивает ленту, а она может отвечать долго: стандартных 15 секунд CIO мало.
                engine { requestTimeout = REQUEST_TIMEOUT_MS }
            }
            val client = http.mcpStreamableHttp(url) {
                authToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }
            return McpToolbox(client, http, client.listTools().tools)
        }

        private const val REQUEST_TIMEOUT_MS = 60_000L
    }
}

package advent.day20.agent

import advent.day20.common.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.*
import io.modelcontextprotocol.kotlin.sdk.types.*
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.time.Duration.Companion.seconds

data class Outcome(val text: String, val data: JsonObject?, val error: Boolean)
class Toolbox {
    private data class Connection(val name: String, val url: String, val http: HttpClient, val client: Client)
    private data class Binding(val connection: Connection, val tool: Tool)
    private val connections = mutableListOf<Connection>()
    private val bindings = linkedMapOf<String, Binding>()
    val status = mutableListOf<JsonObject>()
    val definitions: JsonArray get() = JsonArray(bindings.map { (name, binding) ->
        obj("type" to str("function"), "function" to obj("name" to str(name), "description" to str(binding.tool.description.orEmpty()),
            "parameters" to objectSchema(binding.tool.inputSchema.properties ?: JsonObject(emptyMap()), binding.tool.inputSchema.required.orEmpty())))
    })
    fun server(name: String) = bindings[name]?.connection?.name ?: "unknown"

    suspend fun connect() {
        for ((name, defaultPort, prefix) in listOf(Triple("weather", "8221", "WEATHER"), Triple("news", "8222", "NEWS"), Triple("images", "8223", "IMAGE"))) {
            val url = env("${prefix}_MCP_URL", "http://127.0.0.1:${env("${prefix}_MCP_PORT", defaultPort)}/mcp")
            val http = HttpClient(CIO) { install(SSE); engine { requestTimeout = 300_000 } }
            var connected: Client? = null
            try {
                val client = withTimeout(12_000) { http.mcpStreamableHttp(url) }
                connected = client
                val tools = withTimeout(12_000) { client.listTools().tools }
                val connection = Connection(name, url, http, client)
                connections += connection
                tools.forEach { tool ->
                    val qualified = name + "__" + tool.name
                    check(qualified !in bindings) { "Повтор имени инструмента $qualified" }
                    bindings[qualified] = Binding(connection, tool)
                }
                status += obj("name" to str(name), "url" to str(url), "online" to JsonPrimitive(true), "tools" to JsonArray(tools.map { str(it.name) }))
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                runCatching { connected?.close() }; http.close()
                status += obj("name" to str(name), "url" to str(url), "online" to JsonPrimitive(false), "message" to str("MCP-сервер недоступен"))
            }
        }
    }

    suspend fun call(name: String, arguments: JsonObject): Outcome {
        val binding = bindings[name] ?: return Outcome("Неизвестный инструмент: $name", null, true)
        return try {
            val r = binding.connection.client.callTool(CallToolRequest(CallToolRequestParams(name = binding.tool.name, arguments = arguments)), RequestOptions(timeout = 300.seconds))
            Outcome(r.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }, r.structuredContent, r.isError == true)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Outcome("Ошибка MCP-вызова: ${e.javaClass.simpleName}. Не считай действие выполненным.", null, true) }
    }
    suspend fun close() { connections.forEach { runCatching { it.client.close() }; it.http.close() } }
}

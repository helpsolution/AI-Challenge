package advent.day20.mcp

import advent.day20.common.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.modelcontextprotocol.kotlin.sdk.server.*
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Run three independent instances; each exposes only its own REST service's tools. */
fun main(args: Array<String>) {
    val kind = args.firstOrNull() ?: env("MCP_KIND", "weather")
    val defaults = when (kind) { "weather" -> 8221 to 8211; "news" -> 8222 to 8212; "images" -> 8223 to 8213; else -> error("MCP_KIND: weather, news, images") }
    val prefix = if (kind == "images") "IMAGE" else kind.uppercase()
    val port = env("${prefix}_MCP_PORT", defaults.first.toString()).toInt()
    val base = env("${prefix}_API_URL", "http://127.0.0.1:${env("${prefix}_PORT", defaults.second.toString())}").trimEnd('/')
    val http = RemoteHttp(270)
    embeddedServer(CIO, port = port, host = "127.0.0.1") {
        routing { health() }
        mcpStatelessStreamableHttp(path = "/mcp", allowedHosts = listOf("localhost", "127.0.0.1", "[::1]")) {
            Server(Implementation("day20-$kind", "1.0.0"), ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)))).apply {
                specs(kind).forEach { spec ->
                    addTool(name = spec.name, description = spec.description,
                        inputSchema = ToolSchema(properties = spec.properties, required = spec.required),
                        toolAnnotations = ToolAnnotations(readOnlyHint = !spec.post, destructiveHint = false, idempotentHint = !spec.post, openWorldHint = true)) { request ->
                        try {
                            val arguments = request.arguments ?: JsonObject(emptyMap())
                            validateArguments(spec, arguments)
                            val result = if (spec.post) http.json(base + spec.path, arguments)
                            else http.json(base + spec.path + "?" + arguments.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value.jsonPrimitive.content)}" })
                            CallToolResult(content = listOf(TextContent(result.toString())), structuredContent = result)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { CallToolResult(content = listOf(TextContent(e.message ?: "Ошибка сервиса")), isError = true) }
                    }
                }
            }
        }
    }.start(wait = true)
}

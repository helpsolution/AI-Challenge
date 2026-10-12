package advent.rag.project

import advent.rag.agent.ToolProof
import advent.rag.trace.Recorder
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

@Component
class ProjectMcpClient(private val properties: ProjectProperties, private val json: JsonMapper) {
    fun fetch(names: List<String>, scope: String, trace: Recorder): List<ToolProof> = runBlocking {
        val http = HttpClient(CIO) { install(SSE); engine { requestTimeout = 10_000L } }
        try {
            withTimeout(15_000L) {
                val client = http.mcpStreamableHttp(properties.mcpUrl)
                try {
                    val available = client.listTools().tools.map { it.name }.toSet()
                    names.map { name ->
                        val start = System.nanoTime()
                        val arguments = if (name == "list_files") buildJsonObject {
                            put("scope", scope); put("limit", 160)
                        } else buildJsonObject {}
                        val proof = try {
                            check(name in available) { "MCP-сервер не объявил $name" }
                            val result = client.callTool(CallToolRequest(CallToolRequestParams(name, arguments)))
                            check(result.isError != true) {
                                result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
                            }
                            val structured = result.structuredContent ?: error("MCP не вернул structuredContent")
                            @Suppress("UNCHECKED_CAST")
                            val data = json.readValue(structured.toString(), Map::class.java) as Map<String, Any?>
                            @Suppress("UNCHECKED_CAST")
                            val args = json.readValue(arguments.toString(), Map::class.java) as Map<String, Any>
                            ToolProof(name, args, data, null, (System.nanoTime() - start) / 1_000_000)
                        } catch (e: Exception) {
                            ToolProof(name, emptyMap(), null, e.message ?: "Ошибка MCP", (System.nanoTime() - start) / 1_000_000)
                        }
                        trace.add("MCP · $name", if (proof.error == null) "ok" else "error", proof.durationMs, proof)
                        proof
                    }
                } finally { client.close() }
            }
        } catch (e: Exception) {
            names.map { name ->
                ToolProof(name, emptyMap(), null, "MCP недоступен: ${e.message ?: e.javaClass.simpleName}", 0).also {
                    trace.add("MCP · $name", "error", 0, it)
                }
            }
        } finally { http.close() }
    }
}

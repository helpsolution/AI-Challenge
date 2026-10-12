package advent.project.mcp

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.modelcontextprotocol.kotlin.sdk.server.*
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.json.*
import java.nio.file.Path

fun main() {
    val root = Path.of(System.getenv("PROJECT_ROOT") ?: "..")
    val repo = ProjectRepository(root)
    val port = System.getenv("MCP_PORT")?.toIntOrNull() ?: 8312
    embeddedServer(CIO, host = "127.0.0.1", port = port) {
        routing { get("/health") { call.respondText("ok") } }
        mcpStatelessStreamableHttp(path = "/mcp", allowedHosts = listOf("localhost", "127.0.0.1", "[::1]")) {
            Server(Implementation("project-mcp", "1.0.0"), ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))
            )).apply { registerProjectTools(repo) }
        }
    }.start(wait = true)
}

fun Server.registerProjectTools(repo: ProjectRepository) {
    val annotations = ToolAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false)
    addTool("git_branch", "Текущая git-ветка, короткий commit и detached HEAD. Получать заново для каждого запроса.",
        ToolSchema(properties = buildJsonObject {}), toolAnnotations = annotations) {
        result { repo.branch() }
    }
    addTool("git_status", "Текущее состояние рабочего дерева: статусы и пути изменённых файлов. Содержимое файлов не читает.",
        ToolSchema(properties = buildJsonObject {}), toolAnnotations = annotations) {
        result { repo.status() }
    }
    addTool("list_files", "Список публичных файлов выбранного dayN или всего репозитория. Учитывает .gitignore, результат ограничен limit.",
        ToolSchema(properties = buildJsonObject {
            putJsonObject("scope") { put("type", "string"); put("description", "repo или dayN"); put("default", "repo") }
            putJsonObject("limit") { put("type", "integer"); put("minimum", 1); put("maximum", 300); put("default", 120) }
        }), toolAnnotations = annotations) { request ->
        result {
            val scope = request.arguments?.get("scope")?.jsonPrimitive?.content ?: "repo"
            val limit = request.arguments?.get("limit")?.jsonPrimitive?.int ?: 120
            repo.files(scope, limit)
        }
    }
}

private fun result(fetch: () -> JsonObject): CallToolResult = try {
    val data = fetch()
    CallToolResult(content = listOf(TextContent(data.toString())), structuredContent = data)
} catch (e: Exception) {
    CallToolResult(content = listOf(TextContent(e.message ?: "Инструмент не выполнен")), isError = true)
}

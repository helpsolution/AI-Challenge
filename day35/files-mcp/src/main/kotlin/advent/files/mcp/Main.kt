package advent.files.mcp

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.modelcontextprotocol.kotlin.sdk.server.*
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.json.*
import java.nio.file.Path

fun main() {
    val files = ProjectFiles(Path.of(System.getenv("PROJECT_DIR") ?: "../day32/sandbox"), Path.of(System.getenv("WORKSPACES_DIR") ?: "data/workspaces"))
    val port = System.getenv("MCP_PORT")?.toIntOrNull() ?: 8351
    embeddedServer(CIO, host = "127.0.0.1", port = port) {
        routing { get("/health") { call.respondText("ok") } }
        mcpStatelessStreamableHttp(path = "/mcp", allowedHosts = listOf("localhost", "127.0.0.1", "[::1]")) {
            Server(Implementation("project-files", "1.0.0"), ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))
            )).apply { registerFileTools(files) }
        }
    }.start(wait = true)
}

fun Server.registerFileTools(files: ProjectFiles) {
    val read = ToolAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false)
    val write = ToolAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false)
    addTool("get_project", "Сведения о настроенном локальном проекте. Служебный инструмент интерфейса.",
        ToolSchema(properties = buildJsonObject {}), toolAnnotations = read) { result { Json.encodeToJsonElement(files.projectInfo()) } }
    addTool("create_workspace", "Создать снимок настроенного проекта и отдельную рабочую копию. Служебный инструмент входа пайплайна.",
        ToolSchema(properties = buildJsonObject {}), toolAnnotations = write) { result { Json.encodeToJsonElement(files.createWorkspace()) } }
    addTool("list_files", "Список всех текстовых файлов текущего проекта, пути и размеры. Начни с обзора структуры.",
        schema(), toolAnnotations = read) { r -> result { buildJsonObject { put("files", Json.encodeToJsonElement(files.listFiles(r.text("workspace_id")))) } } }
    addTool("search_text", "Поиск буквального текста без учёта регистра по всем файлам: пути, строки и текст. Для доказательства прочитай найденные файлы через read_file.",
        schema("query"), toolAnnotations = read) { r -> result { Json.encodeToJsonElement(files.search(r.text("workspace_id"), r.text("query"))) } }
    addTool("read_file", "Прочитать файл полностью. Возвращает content, numbered с номерами строк и sha256 для последующего изменения.",
        schema("path"), toolAnnotations = read) { r -> result { Json.encodeToJsonElement(files.readFile(r.text("workspace_id"), r.text("path"))) } }
    addTool("write_file", "Сохранить полный текст README.md либо Markdown в docs/ в рабочей копии. Для существующего файла сначала read_file; передай его sha256 как expected_hash. Новый файл создаётся без expected_hash. Исходный проект не меняется.",
        schema("path", "content", optional = listOf("expected_hash")), toolAnnotations = write) { r -> result {
            val saved = files.writeFile(r.text("workspace_id"), r.text("path"), r.text("content"), r.arguments?.get("expected_hash")?.jsonPrimitive?.contentOrNull)
            buildJsonObject { put("path", saved.path); put("sha256", saved.sha256); put("saved", true) }
        } }
    addTool("get_changes", "Изменённые и созданные файлы с полным текстом и unified diff, список прочитанных файлов. Проверь результат после записи.",
        schema(), toolAnnotations = read) { r -> result { Json.encodeToJsonElement(files.changes(r.text("workspace_id"))) } }
}

private fun schema(vararg fields: String, optional: List<String> = emptyList()) = ToolSchema(
    properties = buildJsonObject {
        (listOf("workspace_id") + fields + optional).forEach { name -> putJsonObject(name) { put("type", "string") } }
    }, required = listOf("workspace_id") + fields,
)

private fun CallToolRequest.text(name: String): String = arguments?.get(name)?.jsonPrimitive?.contentOrNull
    ?: throw IllegalArgumentException("Нужен $name")

private fun result(block: () -> JsonElement): CallToolResult = try {
    val data = block().jsonObject
    CallToolResult(content = listOf(TextContent(data.toString())), structuredContent = data)
} catch (e: Exception) {
    CallToolResult(content = listOf(TextContent(e.message ?: "Инструмент не выполнен")), isError = true)
}

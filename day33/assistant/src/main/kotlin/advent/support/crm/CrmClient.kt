package advent.support.crm

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

@ConfigurationProperties("crm")
data class CrmProperties(val mcpUrl: String, val autoTools: List<String>, val confirmTools: List<String>)

// Описание инструмента так, как его объявил MCP-сервер; inputSchema — JSON Schema аргументов.
data class CrmToolInfo(val name: String, val description: String, val inputSchema: JsonNode, val readOnly: Boolean?)

data class CrmCall(val name: String, val arguments: Map<String, Any?>, val data: JsonNode?, val error: String?, val durationMs: Long) {
    val ok: Boolean get() = error == null
}

class CrmUnavailable(message: String) : RuntimeException(message)

@Component
class CrmClient(private val properties: CrmProperties, private val json: JsonMapper) {
    val url: String get() = properties.mcpUrl

    // Одна MCP-сессия на запрос: все вызовы инструментов одного ответа идут через неё.
    fun <T> session(block: (CrmSession) -> T): T {
        val http = HttpClient(CIO) { install(SSE); engine { requestTimeout = 10_000L } }
        try {
            val client = try {
                runBlocking { withTimeout(10_000L) { http.mcpStreamableHttp(properties.mcpUrl) } }
            } catch (e: Exception) {
                throw CrmUnavailable("CRM MCP недоступен по ${properties.mcpUrl}: ${e.message ?: e.javaClass.simpleName}")
            }
            try { return block(CrmSession(client, json)) } finally { runBlocking { client.close() } }
        } finally { http.close() }
    }
}

class CrmSession(private val client: Client, private val json: JsonMapper) {
    fun tools(): List<CrmToolInfo> = runBlocking {
        withTimeout(10_000L) { client.listTools() }.tools.map { tool ->
            val schema = json.createObjectNode().put("type", "object")
            schema.set("properties", json.readTree(tool.inputSchema.properties?.toString() ?: "{}"))
            tool.inputSchema.required?.let { required -> schema.set("required", json.valueToTree(required)) }
            CrmToolInfo(tool.name, tool.description.orEmpty(), schema, tool.annotations?.readOnlyHint)
        }
    }

    fun call(name: String, arguments: Map<String, Any?>): CrmCall {
        val start = System.nanoTime()
        fun elapsed() = (System.nanoTime() - start) / 1_000_000
        return try {
            val args = Json.parseToJsonElement(json.writeValueAsString(arguments)).jsonObject
            val result = runBlocking { withTimeout(15_000L) { client.callTool(CallToolRequest(CallToolRequestParams(name, args))) } }
            val text = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
            if (result.isError == true) CrmCall(name, arguments, null, text.ifBlank { "Инструмент вернул ошибку" }, elapsed())
            else CrmCall(name, arguments, json.readTree(result.structuredContent?.toString() ?: text), null, elapsed())
        } catch (e: Exception) {
            CrmCall(name, arguments, null, "Вызов MCP не выполнен: ${e.message ?: e.javaClass.simpleName}", elapsed())
        }
    }
}

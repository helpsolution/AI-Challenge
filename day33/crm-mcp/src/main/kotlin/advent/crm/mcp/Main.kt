package advent.crm.mcp

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.modelcontextprotocol.kotlin.sdk.server.*
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.json.*
import java.nio.file.Path

fun main() {
    val crm = CrmRepository(Path.of(System.getenv("CRM_FILE") ?: "product/crm/crm.json"))
    val port = System.getenv("MCP_PORT")?.toIntOrNull() ?: 8331
    embeddedServer(CIO, host = "127.0.0.1", port = port) {
        routing { get("/health") { call.respondText("ok") } }
        mcpStatelessStreamableHttp(path = "/mcp", allowedHosts = listOf("localhost", "127.0.0.1", "[::1]")) {
            Server(Implementation("crm-mcp", "1.0.0"), ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))
            )).apply { registerCrmTools(crm) }
        }
    }.start(wait = true)
}

fun Server.registerCrmTools(crm: CrmRepository) {
    val read = ToolAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false)
    val write = ToolAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false)

    addTool("list_customers", "Все клиенты CRM: имя, email, рабочее пространство и тариф. Служебный инструмент для выбора клиента, не для ответа клиенту.",
        ToolSchema(properties = buildJsonObject {}), toolAnnotations = read) {
        result { Json.encodeToJsonElement(crm.customers()) }
    }
    addTool("get_customer", "Профиль клиента: статус аккаунта и блокировка входа, способы входа, 2FA, устройства и версии приложения, рабочее пространство, его тариф, владелец, администраторы и режим «Только SSO».",
        schema(), toolAnnotations = read) { request ->
        result { Json.encodeToJsonElement(crm.profile(request.customerId)) }
    }
    addTool("get_subscription", "Подписка рабочего пространства клиента: тариф, статус оплаты (active, past_due, read_only), оплачено до, с какого момента режим «Только чтение», владелец. Карту и платежи видит только владелец.",
        schema(), toolAnnotations = read) { request ->
        result { Json.encodeToJsonElement(crm.subscription(request.customerId)) }
    }
    addTool("get_login_events", "Журнал попыток входа клиента, новые сверху: время, устройство и версия приложения, способ входа, результат (success, wrong_password, locked, sso_required, session_not_created, password_reset) и пояснение.",
        schema { putJsonObject("limit") { put("type", "integer"); put("description", "Сколько последних событий вернуть, 1–50"); put("default", 10) } },
        toolAnnotations = read) { request ->
        result { Json.encodeToJsonElement(crm.loginEvents(request.customerId, request.arguments?.get("limit")?.jsonPrimitive?.intOrNull ?: 10)) }
    }
    addTool("list_tickets", "Обращения клиента в поддержку: номер, тема, статус, даты и число сообщений.",
        schema { putJsonObject("status") { put("type", "string"); putJsonArray("enum") { add("open"); add("closed"); add("all") }; put("default", "all") } },
        toolAnnotations = read) { request ->
        result { Json.encodeToJsonElement(crm.tickets(request.customerId, request.arguments?.get("status")?.jsonPrimitive?.contentOrNull ?: "all")) }
    }
    addTool("get_ticket", "Обращение клиента целиком, с перепиской. Возвращает только обращения этого клиента.",
        schema(listOf("ticket_id")) { putJsonObject("ticket_id") { put("type", "integer"); put("description", "Номер обращения") } },
        toolAnnotations = read) { request ->
        result {
            val ticketId = request.arguments?.get("ticket_id")?.jsonPrimitive?.intOrNull ?: throw IllegalArgumentException("Нужен номер обращения ticket_id")
            Json.encodeToJsonElement(crm.ticket(request.customerId, ticketId))
        }
    }
    addTool("create_ticket", "Создать обращение в поддержку от имени клиента, чтобы им занялся сотрудник. Запись в CRM.",
        schema(listOf("subject", "message")) {
            putJsonObject("subject") { put("type", "string"); put("description", "Короткая тема обращения") }
            putJsonObject("message") { put("type", "string"); put("description", "Суть проблемы и что уже выяснено, от первого лица клиента") }
        }, toolAnnotations = write) { request ->
        result {
            val subject = request.arguments?.get("subject")?.jsonPrimitive?.contentOrNull.orEmpty()
            val message = request.arguments?.get("message")?.jsonPrimitive?.contentOrNull.orEmpty()
            Json.encodeToJsonElement(crm.createTicket(request.customerId, subject, message))
        }
    }
}

// Все инструменты клиента принимают customer_id: CRM не знает, кто спрашивает, это решает вызывающая сторона.
private fun schema(required: List<String> = emptyList(), extra: JsonObjectBuilder.() -> Unit = {}) = ToolSchema(
    properties = buildJsonObject {
        putJsonObject("customer_id") { put("type", "string"); put("description", "Идентификатор клиента, например c-101") }
        extra()
    },
    required = listOf("customer_id") + required,
)

private val CallToolRequest.customerId: String
    get() = arguments?.get("customer_id")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Нужен customer_id")

private fun result(fetch: () -> JsonElement): CallToolResult = try {
    val data = fetch().jsonObject
    CallToolResult(content = listOf(TextContent(data.toString())), structuredContent = data)
} catch (e: Exception) {
    CallToolResult(content = listOf(TextContent(e.message ?: "Инструмент не выполнен")), isError = true)
}

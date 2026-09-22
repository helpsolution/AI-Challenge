package advent.users.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Два инструмента поверх REST API сервиса пользователей.
 * Описания и схемы — это ровно то, по чему модель сама поймёт, когда и как их звать.
 */
fun Server.registerUserTools(api: UsersApi) {
    addTool(
        name = "create_user",
        description = """
            Создать пользователя: нужны имя и email.
            Email уникален и служит ключом пользователя, регистр не важен.
            Если пользователь с таким email уже есть, инструмент вернёт ошибку и дубль не создаст —
            поэтому перед созданием имеет смысл поискать через find_user.
        """.trimIndent(),
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "Имя пользователя, например \"Иван Петров\"")
                    put("minLength", 1)
                    put("maxLength", MAX_NAME_LENGTH)
                }
                putJsonObject("email") {
                    put("type", "string")
                    put("format", "email")
                    put("description", "Email пользователя, например \"ivan@example.com\"")
                    put("maxLength", MAX_EMAIL_LENGTH)
                }
            },
            required = listOf("name", "email"),
        ),
        outputSchema = ToolSchema(
            properties = buildJsonObject { put("user", USER_SCHEMA) },
            required = listOf("user"),
        ),
        toolAnnotations = ToolAnnotations(
            title = "Создать пользователя",
            readOnlyHint = false,
            destructiveHint = false,
            idempotentHint = false,
            openWorldHint = true,
        ),
    ) { request ->
        val name = request.stringArgument("name")
            ?: return@addTool failure("Не передан обязательный аргумент name")
        val email = request.stringArgument("email")
            ?: return@addTool failure("Не передан обязательный аргумент email")

        respond {
            val user = api.create(name, email)
            ToolPayload(
                text = "Создан пользователь #${user.id}: ${user.name} <${user.email}>",
                structured = buildJsonObject { put("user", user.toJson()) },
            )
        }
    }

    addTool(
        name = "find_user",
        description = """
            Найти пользователей по части имени или email: регистр не важен, ищется вхождение подстроки.
            Подходит, когда точный email неизвестен. Совпадений может быть несколько или ни одного.
        """.trimIndent(),
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Часть имени или email, например \"иван\" или \"@example.com\"")
                    put("minLength", 1)
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Сколько результатов вернуть, от 1 до $MAX_LIMIT")
                    put("minimum", 1)
                    put("maximum", MAX_LIMIT)
                    put("default", DEFAULT_LIMIT)
                }
            },
            required = listOf("query"),
        ),
        outputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("count") {
                    put("type", "integer")
                    put("description", "Сколько пользователей найдено")
                }
                putJsonObject("users") {
                    put("type", "array")
                    put("items", USER_SCHEMA)
                }
            },
            required = listOf("count", "users"),
        ),
        toolAnnotations = ToolAnnotations(
            title = "Найти пользователя",
            readOnlyHint = true,
            idempotentHint = true,
            openWorldHint = true,
        ),
    ) { request ->
        val query = request.stringArgument("query")
            ?: return@addTool failure("Не передан обязательный аргумент query")
        val limit = request.intArgument("limit") ?: DEFAULT_LIMIT

        respond {
            val users = api.find(query, limit)
            ToolPayload(
                text = if (users.isEmpty()) {
                    "По запросу \"$query\" никого не найдено"
                } else {
                    users.joinToString("\n") { "#${it.id} ${it.name} <${it.email}>, создан ${it.createdAt}" }
                },
                structured = buildJsonObject {
                    put("count", users.size)
                    putJsonArray("users") { users.forEach { add(it.toJson()) } }
                },
            )
        }
    }
}

private const val DEFAULT_LIMIT = 20
private const val MAX_LIMIT = 100
private const val MAX_NAME_LENGTH = 100
private const val MAX_EMAIL_LENGTH = 254

/** Описание пользователя переиспользуется обеими схемами результата. */
private val USER_SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("id") {
            put("type", "integer")
            put("description", "Идентификатор пользователя")
        }
        putJsonObject("name") {
            put("type", "string")
            put("description", "Имя")
        }
        putJsonObject("email") {
            put("type", "string")
            put("description", "Email в нижнем регистре")
        }
        putJsonObject("createdAt") {
            put("type", "string")
            put("description", "Момент создания в UTC, ISO-8601")
        }
    }
    putJsonArray("required") {
        add("id")
        add("name")
        add("email")
        add("createdAt")
    }
}

/**
 * Результат инструмента идёт в двух видах сразу: текстом для модели
 * и структурой в structuredContent — по схеме из outputSchema, чтобы вызывающий код
 * мог взять из ответа готовый id, а не разбирать строку.
 */
private class ToolPayload(val text: String, val structured: JsonObject)

/** Ошибку сервиса отдаём как ошибку инструмента, а не как падение соединения: модель увидит причину и сможет исправиться. */
private suspend fun respond(block: suspend () -> ToolPayload): CallToolResult = try {
    val payload = block()
    CallToolResult(
        content = listOf(TextContent(payload.text)),
        structuredContent = payload.structured,
    )
} catch (e: UsersApiException) {
    failure(e.message ?: "Сервис пользователей недоступен")
}

private fun failure(message: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(message)), isError = true)

private fun User.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("name", name)
    put("email", email)
    put("createdAt", createdAt)
}

private fun CallToolRequest.stringArgument(name: String): String? =
    arguments?.get(name)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

private fun CallToolRequest.intArgument(name: String): Int? =
    arguments?.get(name)?.jsonPrimitive?.intOrNull

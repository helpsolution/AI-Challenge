package advent.users.mcp

import advent.users.mcp.ServerConfig.Companion.MCP_PATH
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * MCP-сервер на транспорте Streamable HTTP: клиент обращается к нему по обычному HTTP,
 * поэтому сервер можно вынести куда угодно — в контейнер, на сервер, в облако.
 *
 * Режим stateless: сессии нет, каждый запрос самодостаточен. Это делает сервер
 * горизонтально масштабируемым — запросы можно раскидывать по репликам без sticky-сессий.
 */
fun main() {
    val config = ServerConfig.fromEnvironment()
    val log = LoggerFactory.getLogger("advent.users.mcp")

    log.info("MCP-сервер пользователей: порт {}, сервис пользователей {}", config.port, config.usersApiUrl)
    log.info("Разрешённые значения Host: {}", config.allowedHosts.joinToString(", "))
    if (config.authToken == null) {
        log.warn("MCP_AUTH_TOKEN не задан: {} отвечает без проверки доступа. Допустимо только локально.", MCP_PATH)
    }

    embeddedServer(CIO, port = config.port, host = "0.0.0.0") {
        usersMcpModule(config)
    }.start(wait = true)
}

fun Application.usersMcpModule(config: ServerConfig) {
    val api = UsersApi(config.usersApiUrl)
    requireBearerToken(config.authToken)

    routing {
        // Проба живости для облачных платформ: они ждут дешёвый GET, а /mcp отвечает только на POST.
        get("/health") { call.respondText("ok") }
    }

    mcpStatelessStreamableHttp(
        path = MCP_PATH,
        // Защита от DNS rebinding: сервер отвечает только на те значения Host, которые ему разрешили.
        // По умолчанию это localhost — в облаке сюда нужно добавить домен развёртывания.
        allowedHosts = config.allowedHosts,
    ) {
        Server(
            serverInfo = Implementation(name = "user-service-mcp", version = "1.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
            ),
        ).apply { registerUserTools(api) }
    }
}

/**
 * Токен — единственная преграда перед инструментами: create_user меняет данные,
 * и открытый наружу эндпоинт без проверки доступа означает, что писать в базу может кто угодно.
 * Если MCP_AUTH_TOKEN не задан, проверки нет — это режим локальной разработки.
 */
private fun Application.requireBearerToken(token: String?) {
    if (token == null) return
    val expected = token.toByteArray()

    intercept(ApplicationCallPipeline.Plugins) {
        if (!call.request.path().startsWith(MCP_PATH)) return@intercept

        val header = call.request.header(HttpHeaders.Authorization).orEmpty()
        val presented = header.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
            ?.substring(BEARER_PREFIX.length)
            ?.trim()
            .orEmpty()

        // Сравнение за постоянное время: обычное сравнение строк подсказывает длину общего префикса.
        if (!MessageDigest.isEqual(presented.toByteArray(), expected)) {
            call.respondText(
                UNAUTHORIZED_BODY,
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized,
            )
            finish()
        }
    }
}

private const val BEARER_PREFIX = "Bearer "

/** Отказ в формате JSON-RPC: клиент MCP ждёт именно его, а не страницу с ошибкой. */
private const val UNAUTHORIZED_BODY =
    """{"jsonrpc":"2.0","id":null,"error":{"code":-32001,"message":"Unauthorized: нужен заголовок Authorization: Bearer <token>"}}"""

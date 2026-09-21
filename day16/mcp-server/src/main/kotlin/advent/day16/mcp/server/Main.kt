package advent.day16.mcp.server

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.slf4j.LoggerFactory
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream

private const val DEFAULT_WEATHER_API_URL = "http://localhost:8096"

/**
 * MCP-сервер на транспорте stdio: клиент запускает его как дочерний процесс
 * и общается с ним через стандартный ввод-вывод.
 *
 * Отсюда главное ограничение stdio: в stdout может быть только протокол.
 * Одна посторонняя строка — и клиент получает вместо JSON-RPC мусор.
 */
fun main() {
    // Забираем настоящий stdout себе под протокол и уводим System.out в stderr:
    // теперь ни println в коде, ни приветствие случайной библиотеки не сломают соединение.
    val protocolOutput = FileOutputStream(FileDescriptor.out)
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.err), true))

    val log = LoggerFactory.getLogger("advent.day16.mcp.server")
    val weatherApiUrl = System.getenv("WEATHER_API_URL") ?: DEFAULT_WEATHER_API_URL
    log.info("MCP-сервер погоды запускается, бизнес-сервис: {}", weatherApiUrl)

    val server = Server(
        serverInfo = Implementation(name = "day16-weather", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
        ),
    )
    val weatherApi = WeatherApi(weatherApiUrl)
    server.registerWeatherTools(weatherApi)

    val transport = StdioServerTransport(
        input = System.`in`.asSource().buffered(),
        output = protocolOutput.asSink().buffered(),
    ) {}

    runBlocking {
        val session = server.createSession(transport)
        val closed = Job()
        session.onClose { closed.complete() }
        closed.join()
        log.info("Соединение закрыто, сервер завершает работу")
    }
}

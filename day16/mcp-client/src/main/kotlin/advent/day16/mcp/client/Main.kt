package advent.day16.mcp.client

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit

private const val SERVER_LIBS_PATH = "mcp-server/build/libs"
private const val PROJECT_DIR = "day16"

/**
 * Минимальный MCP-клиент: запускает сервер как дочерний процесс, устанавливает соединение
 * и печатает список инструментов, которые сервер объявил.
 */
fun main(args: Array<String>) = runBlocking {
    val serverJar = locateServerJar(args.firstOrNull())

    println("1. Запускаю MCP-сервер: java -jar ${serverJar.path}")
    val process = ProcessBuilder("java", "-jar", serverJar.path)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()

    val client = Client(clientInfo = Implementation(name = "day16-mcp-client", version = "1.0.0"))
    val transport = StdioClientTransport(
        input = process.inputStream.asSource().buffered(),
        output = process.outputStream.asSink().buffered(),
    )

    try {
        println("2. Устанавливаю соединение (initialize)...")
        client.connect(transport)

        val server = client.serverVersion
        println("   Соединение установлено.")
        println("   Сервер: ${server?.name ?: "не представился"} ${server?.version.orEmpty()}")
        println("   Возможности сервера: ${client.serverCapabilities ?: "не объявлены"}")
        client.serverInstructions?.let { println("   Инструкция сервера: $it") }

        println()
        println("3. Запрашиваю список инструментов (tools/list)...")
        val tools = client.listTools().tools
        println("   Сервер объявил инструментов: ${tools.size}")
        println()
        tools.forEach { println(it.describe()) }
    } finally {
        client.close()
        process.destroy()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }
    }
}

/** Печатаем инструмент так, как его видит модель: имя, описание и схема аргументов. */
private fun Tool.describe(): String = buildString {
    appendLine("• $name")
    // Описание может быть многострочным — выравниваем все строки под один отступ.
    description?.lines()?.forEach { appendLine("    ${it.trim()}") }
    val properties: JsonObject? = inputSchema.properties
    if (properties.isNullOrEmpty()) {
        appendLine("    аргументов нет")
        return@buildString
    }
    appendLine("    аргументы:")
    properties.forEach { (argument, definition) ->
        val type = definition.jsonObject["type"]?.jsonPrimitive?.content ?: "?"
        val required = if (inputSchema.required?.contains(argument) == true) ", обязательный" else ""
        val comment = definition.jsonObject["description"]?.jsonPrimitive?.content?.let { " — $it" }.orEmpty()
        appendLine("      - $argument ($type$required)$comment")
    }
}

/**
 * Ищем jar сервера, не завися от того, откуда запущен клиент: из Gradle, из IDE или из терминала.
 * Порядок: аргумент командной строки, переменная MCP_SERVER_JAR, поиск вверх по дереву каталогов.
 */
private fun locateServerJar(explicitPath: String?): File {
    explicitPath?.let { return File(it).absoluteFile.requireJar() }
    System.getenv("MCP_SERVER_JAR")?.let { return File(it).absoluteFile.requireJar() }

    var directory: File? = File("").absoluteFile
    while (directory != null) {
        val found = directory.findServerJar() ?: File(directory, PROJECT_DIR).findServerJar()
        if (found != null) return found
        directory = directory.parentFile
    }

    error(
        """
            Не найден jar MCP-сервера (искали $SERVER_LIBS_PATH вверх от ${File("").absolutePath}).
            Соберите его: cd $PROJECT_DIR && ./gradlew :mcp-server:jar
            Либо укажите путь явно: аргументом командной строки или переменной MCP_SERVER_JAR.
        """.trimIndent(),
    )
}

/** Имя jar содержит версию, поэтому берем самый свежий файл, а не фиксированное имя. */
private fun File.findServerJar(): File? =
    File(this, SERVER_LIBS_PATH)
        .listFiles { file -> file.isFile && file.extension == "jar" }
        ?.maxByOrNull { it.lastModified() }

private fun File.requireJar(): File = also {
    check(it.isFile) { "Не найден jar MCP-сервера: $it" }
}

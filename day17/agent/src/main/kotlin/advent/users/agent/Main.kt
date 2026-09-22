package advent.users.agent

import kotlinx.coroutines.runBlocking

/**
 * Агент связывает два клиента: один говорит с языковой моделью, другой — с MCP-сервером.
 * Сам агент про пользователей не знает ничего: что умеет система, он выясняет
 * у MCP-сервера в рантайме через tools/list.
 */
fun main() = runBlocking {
    val config = try {
        AgentConfig.load()
    } catch (e: IllegalStateException) {
        println("Не могу запуститься: ${e.message}")
        return@runBlocking
    }

    println("Подключаюсь к MCP-серверу: ${config.mcpUrl}")
    val toolbox = try {
        McpToolbox.connect(config)
    } catch (e: Exception) {
        println(
            "Не удалось подключиться: ${e.message ?: e::class.simpleName}\n" +
                "Проверьте, что запущены оба сервиса:\n" +
                "  ./gradlew :user-service:bootRun\n" +
                "  ./gradlew :user-service-mcp:run",
        )
        return@runBlocking
    }

    val server = toolbox.serverInfo
    println("Подключился: ${server?.name ?: "сервер не представился"} ${server?.version.orEmpty()}")
    println("Инструменты, которые он объявил:")
    toolbox.tools.forEach { tool ->
        val required = tool.inputSchema.required.orEmpty()
        val args = tool.inputSchema.properties?.keys.orEmpty().joinToString(", ") { name ->
            if (name in required) "$name*" else name
        }
        println("  • ${tool.name}($args)")
    }
    println("\nМодель: ${config.model}.")
    println("Команды: «/raw» — показывать обмен с LLM целиком, «выход» — закончить.")

    val trace = Trace()

    DeepSeekClient(config).use { llm ->
        val agent = Agent(llm, toolbox, trace)

        while (true) {
            print("\nВы: ")
            System.out.flush()
            val line = readlnOrNull()?.trim() ?: break
            if (line.isEmpty()) continue
            if (line.lowercase() in EXIT_WORDS) break
            if (line.lowercase() in RAW_WORDS) {
                trace.raw = !trace.raw
                println(
                    if (trace.raw) {
                        "Показываю обмен с LLM целиком: тот же JSON, что ушёл в сеть, только с отступами."
                    } else {
                        "Обмен с LLM больше не показываю."
                    },
                )
                continue
            }

            val answer = try {
                agent.ask(line)
            } catch (e: LlmException) {
                "Не получилось: ${e.message}"
            }
            println("\nАгент: $answer")
        }
    }

    toolbox.close()
    println("\nПока.")
}

private val EXIT_WORDS = setOf("выход", "exit", "quit", "q")
private val RAW_WORDS = setOf("/raw", "raw", "/сырое")

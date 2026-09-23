package advent.news.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.minutes

/**
 * Агент работает в двух режимах сразу:
 *  - чат: человек просит подписаться, показать подписки или сводку — модель зовёт инструменты MCP;
 *  - таймер: раз в DIGEST_EVERY_MINUTES агент сам выдаёт сводку за прошедший период.
 *
 * Новости при этом собирает не агент, а сервис новостей по своему расписанию.
 * Агент можно остановить и запустить снова — пропущенное за это время никуда не денется.
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
                "  ./gradlew :news-service:bootRun\n" +
                "  ./gradlew :news-mcp:run",
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
    println("\nМодель: ${config.model}. Сводка сама — каждые ${config.digestEveryMinutes} мин.")
    println("Команды: «/digest» — сводка сейчас, «/raw» — показывать обмен с LLM целиком, «выход» — закончить.")

    val trace = Trace()
    val console = Console()

    DeepSeekClient(config).use { llm ->
        val agent = Agent(llm, toolbox, trace)
        val digester = Digester(llm, toolbox, trace)

        val schedule = launch(Dispatchers.Default) {
            while (isActive) {
                delay(config.digestEveryMinutes.minutes)
                // Сбой одной сводки не должен останавливать таймер: следующая придёт в срок.
                val text = runCatching { digester.digest(config.digestEveryMinutes) }
                    .getOrElse { "⚠️ Сводка не собрана: ${it.message ?: it::class.simpleName}" }
                console.interrupt { println("\n$text") }
            }
        }

        while (true) {
            val line = console.readLine()?.trim()
            if (line == null) {
                // Ввода нет — например, агент запущен в фоне или в контейнере без терминала.
                // Чат недоступен, но сводки по таймеру продолжают выходить: агент работает 24/7.
                console.exclusive { println("\nВвод закрыт — чат выключен, сводки продолжают выходить по таймеру.") }
                schedule.join()
                break
            }
            if (line.isEmpty()) continue
            if (line.lowercase() in EXIT_WORDS) break

            when (line.lowercase()) {
                in RAW_WORDS -> console.exclusive {
                    trace.raw = !trace.raw
                    println(
                        if (trace.raw) {
                            "Показываю обмен с LLM целиком: тот же JSON, что ушёл в сеть, только с отступами."
                        } else {
                            "Обмен с LLM больше не показываю."
                        },
                    )
                }

                in DIGEST_WORDS -> console.exclusive {
                    println(digester.digest(config.digestEveryMinutes))
                }

                else -> console.exclusive {
                    val answer = try {
                        agent.ask(line)
                    } catch (e: LlmException) {
                        "Не получилось: ${e.message}"
                    }
                    println("\nАгент: $answer")
                }
            }
        }

        schedule.cancel()
    }

    toolbox.close()
    println("\nПока.")
}

private val EXIT_WORDS = setOf("выход", "exit", "quit", "q")
private val RAW_WORDS = setOf("/raw", "raw", "/сырое")
private val DIGEST_WORDS = setOf("/digest", "/сводка")

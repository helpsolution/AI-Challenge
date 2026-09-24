package advent.pipeline.bot

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("advent.pipeline.bot")

/**
 * Два режима:
 *  - без аргументов — Telegram-бот: сообщения владельца уходят агенту;
 *  - --ask <вопрос> — один вопрос агенту из терминала: трассировка и отчёт печатаются в консоль.
 *    Удобно проверить конвейер без Telegram.
 */
fun main(args: Array<String>) = runBlocking {
    val config = try {
        BotConfig.load()
    } catch (e: IllegalStateException) {
        log.error("Не могу запуститься: {}", e.message)
        exitProcess(1)
    }

    // Если MCP-сервер ещё не поднялся, бот выходит с ошибкой, а systemd запускает его снова через 10 секунд.
    val toolbox = try {
        McpToolbox.connect(config.mcpUrl, config.mcpAuthToken)
    } catch (e: Exception) {
        log.error("MCP-сервер {} недоступен: {}", config.mcpUrl, e.message ?: e::class.simpleName)
        exitProcess(1)
    }
    log.info("MCP {}: инструменты {}", config.mcpUrl, toolbox.tools.joinToString(", ") { it.name })

    DeepSeekClient(config.deepSeekBaseUrl, config.deepSeekApiKey, config.model).use { llm ->
        val agent = Agent(llm, toolbox)
        if (args.firstOrNull() == "--ask") {
            ask(agent, args.drop(1).joinToString(" "))
        } else {
            serve(config, agent)
        }
    }
    toolbox.close()
}

private suspend fun ask(agent: Agent, question: String) {
    if (question.isBlank()) {
        println("Использование: --ask «собери свежую сводку»")
        return
    }
    val handoff = Handoff()
    val turn = try {
        agent.ask(question) { println(handoff.describe(it)) }
    } catch (e: LlmException) {
        println("⚠️ ${e.message}")
        return
    }
    println("\n💬 ${turn.answer}")
    turn.steps.lastOrNull { it.tool in setOf("report_save", "report_latest") && !it.outcome.isError }
        ?.outcome?.structured
        ?.let { println("\n" + ReportView.plain(it)) }
}

private suspend fun serve(config: BotConfig, agent: Agent) {
    val token = config.telegramToken ?: run {
        log.error("Не задан TELEGRAM_BOT_TOKEN. Токен выдаёт @BotFather. Образец — .env.example.")
        exitProcess(1)
    }
    TelegramClient(token).use { telegram ->
        runCatching { telegram.setMyCommands(Bot.COMMANDS) }
            .onFailure { log.warn("Меню команд не установлено: {}", it.message) }
        if (config.ownerChatId == null) {
            log.warn("TELEGRAM_CHAT_ID не задан: напишите боту /start — он пришлёт chat id.")
        } else {
            log.info("Бот запущен, отвечает чату {}", config.ownerChatId)
        }
        Bot(config, telegram, agent).run()
    }
}

package advent.habr.bot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes

private val log = LoggerFactory.getLogger("advent.habr.bot")

/**
 * Бот работает в двух режимах сразу:
 *  - команды: человек пишет /digest, /status, /on, /off — бот зовёт нужный инструмент MCP;
 *  - таймер: раз в DIGEST_EVERY_MINUTES бот сам присылает сводку того, что собрано после прошлой.
 *
 * Статьи при этом собирает не бот, а сервис по своему расписанию. Бота можно остановить
 * и запустить снова — собранное за это время придёт в следующей сводке.
 */
fun main() = runBlocking {
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

    TelegramClient(config.telegramToken).use { telegram ->
        DeepSeekClient(config.deepSeekBaseUrl, config.deepSeekApiKey, config.model).use { llm ->
            runCatching { telegram.setMyCommands(Bot.COMMANDS) }
                .onFailure { log.warn("Меню команд не установлено: {}", it.message) }

            val digester = Digester(llm, toolbox, CursorStore(config.stateDir))
            val owner = config.ownerChatId
            if (owner == null) {
                log.warn("TELEGRAM_CHAT_ID не задан: напишите боту /start — он пришлёт chat id. Сводки по таймеру выключены.")
            } else {
                log.info("Бот запущен: сводка каждые {} мин в чат {}", config.digestEveryMinutes, owner)
                launch {
                    while (isActive) {
                        delay(config.digestEveryMinutes.minutes)
                        // Сбой одной сводки не должен останавливать таймер: следующая придёт в срок.
                        val text = try {
                            digester.scheduled(config.digestEveryMinutes)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            "⚠️ Сводка не собрана: ${Html.escape(e.message ?: e::class.simpleName.orEmpty())}"
                        }
                        if (text == null) {
                            log.info("Сводка по таймеру: отправлять нечего")
                            continue
                        }
                        try {
                            telegram.sendMessage(owner, text)
                            log.info("Сводка по таймеру отправлена")
                        } catch (e: TelegramException) {
                            log.warn("Сводка не отправлена: {}", e.message)
                        }
                    }
                }
            }

            Bot(config, telegram, toolbox, digester).run()
        }
    }
    toolbox.close()
}

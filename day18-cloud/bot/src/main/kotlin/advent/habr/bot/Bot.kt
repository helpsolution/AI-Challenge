package advent.habr.bot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.seconds

/**
 * Команды бота. Каждая команда — это один инструмент MCP, поэтому модель здесь не нужна:
 * она подключается только внутри /digest, чтобы написать текст сводки.
 */
class Bot(
    private val config: BotConfig,
    private val telegram: TelegramClient,
    private val toolbox: McpToolbox,
    private val digester: Digester,
) {
    private val log = LoggerFactory.getLogger(Bot::class.java)

    suspend fun run() {
        var offset: Long? = null
        while (currentCoroutineContext().isActive) {
            val updates = try {
                telegram.getUpdates(offset)
            } catch (e: TelegramException) {
                log.warn("{}", e.message)
                delay(RETRY_DELAY)
                continue
            }
            for (update in updates) {
                // offset подтверждает получение: без него Telegram присылал бы те же сообщения снова.
                offset = update.updateId + 1
                val message = update.message ?: continue
                try {
                    handle(message)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Одна сломанная команда не должна останавливать бота.
                    log.error("Команда «{}» упала", message.text, e)
                }
            }
        }
    }

    private suspend fun handle(message: TelegramMessage) {
        val text = message.text?.trim() ?: return
        val chatId = message.chat.id
        val owner = config.ownerChatId

        // Режим настройки: владельца ещё нет, и бот умеет только сообщить chat id.
        if (owner == null) {
            if (text.startsWith("/start")) {
                telegram.sendMessage(
                    chatId,
                    "👋 Бот ещё не настроен.\n\nТвой chat id: <code>$chatId</code>\n" +
                        "Впиши его в .env как TELEGRAM_CHAT_ID=$chatId и перезапусти бота.",
                )
                log.info("Прислал chat id {} в режиме настройки", chatId)
            }
            return
        }

        // Бот отвечает только владельцу: иначе любой, кто найдёт его в поиске, тратил бы ключ DeepSeek.
        if (chatId != owner) {
            log.warn("Сообщение из чужого чата {} пропущено", chatId)
            return
        }

        val parts = text.split(WHITESPACE)
        // В группах команда приходит как /digest@имя_бота.
        val command = parts.first().substringBefore('@').lowercase()
        log.info("Команда {}", command)
        when (command) {
            "/start", "/help" -> reply(help())
            "/status" -> reply(status(toolbox.call("news_status")))
            "/on" -> reply(status(toolbox.call("news_set_enabled", enabled(true)), "✅ Сбор включён. Первый сбор уже сделан."))
            "/off" -> reply(
                status(
                    toolbox.call("news_set_enabled", enabled(false)),
                    "⏸ Сбор выключен. Сводки по таймеру не придут, пока не включишь /on.",
                ),
            )

            "/digest" -> {
                val hours = parts.getOrNull(1)?.toIntOrNull() ?: DEFAULT_DIGEST_HOURS
                if (hours !in DIGEST_HOURS) {
                    reply("🤔 Период — от ${DIGEST_HOURS.first} до ${DIGEST_HOURS.last} часов, например /digest 6")
                    return
                }
                reply("⏳ Собираю сводку за $hours ч…")
                reply(digester.onDemand(hours))
            }

            else -> reply("🤔 Не знаю такой команды.\n\n${help()}")
        }
    }

    private suspend fun reply(html: String) = telegram.sendMessage(config.ownerChatId!!, html)

    private fun help(): String = """
        👋 Я присылаю дайджест статей Хабра.

        Сервер сам забирает ленту по расписанию, а я раз в ${config.digestEveryMinutes} мин присылаю сводку того, что появилось.

        /digest — сводка за последние $DEFAULT_DIGEST_HOURS ч, /digest 6 — за 6 ч
        /status — что со сборщиком
        /on — включить сбор и сводки
        /off — выключить
    """.trimIndent()

    /** Состояние пишется из structuredContent: так в нём можно показать и интервал сводок, который знает только бот. */
    private fun status(outcome: ToolOutcome, headline: String? = null): String {
        val status = outcome.structured
        if (outcome.isError || status == null) return "⚠️ ${Html.escape(outcome.text)}"

        val lines = mutableListOf<String>()
        headline?.let { lines += it }
        val enabled = status.boolean("enabled") == true
        lines += if (enabled) {
            "🟢 Сбор включён · ${status.string("source")} раз в ${status.int("everyMinutes")} мин"
        } else {
            "⏸ Сбор выключен"
        }
        val lastRun = status["lastRun"] as? JsonObject
        lines += when {
            lastRun == null -> "🕐 Ещё ни разу не собирали"
            lastRun.string("error") != null -> "⚠️ Последний сбор ${time(lastRun.string("at"))} не удался: ${lastRun.string("error")}"
            else -> "🕐 Последний сбор ${time(lastRun.string("at"))}: в ленте ${lastRun.int("found")}, новых ${lastRun.int("added")}"
        }
        status.string("nextRunAt")?.let { lines += "⏭ Следующий сбор в ${time(it)}" }
        val articles = status.int("articles") ?: 0
        lines += "🗄 В базе $articles ${plural(articles, "статья", "статьи", "статей")}, храним ${status.int("retentionDays")} дней"
        if (enabled) lines += "🗞 Сводка приходит раз в ${config.digestEveryMinutes} мин"
        return lines.joinToString("\n") { Html.escape(it) }
    }

    private fun enabled(value: Boolean): JsonObject = buildJsonObject { put("enabled", value) }

    private fun time(instant: String?): String = instant?.let { CLOCK.format(Instant.parse(it)) } ?: "?"

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull

    private fun JsonObject.boolean(name: String): Boolean? = this[name]?.jsonPrimitive?.booleanOrNull

    companion object {
        /** Меню команд у поля ввода в Telegram. */
        val COMMANDS = listOf(
            "digest" to "Сводка за последние сутки (или /digest 6 — за 6 ч)",
            "status" to "Что со сборщиком",
            "on" to "Включить сбор и сводки",
            "off" to "Выключить сбор и сводки",
            "help" to "Что умеет бот",
        )

        private const val DEFAULT_DIGEST_HOURS = 24
        private val DIGEST_HOURS = 1..72
        private val WHITESPACE = Regex("\\s+")
        private val RETRY_DELAY = 5.seconds
        private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault())
    }
}

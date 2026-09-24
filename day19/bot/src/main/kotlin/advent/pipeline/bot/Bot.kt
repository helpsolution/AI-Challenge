package advent.pipeline.bot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

/**
 * Telegram-сторона агента. Любое сообщение владельца уходит агенту, команды — это просто готовые фразы.
 *
 * Пока агент работает, бот держит одно сообщение-трассировку и дописывает в него каждый вызов
 * инструмента вместе с проверкой передачи данных. Отчёт, если он получился, приходит отдельным сообщением.
 */
class Bot(
    private val config: BotConfig,
    private val telegram: TelegramClient,
    private val agent: Agent,
) {
    private val log = LoggerFactory.getLogger(Bot::class.java)
    private val handoff = Handoff()

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
                    // Одно сломанное сообщение не должно останавливать бота.
                    log.error("Сообщение «{}» не обработано", message.text?.take(100), e)
                    runCatching { reply("⚠️ Не получилось: ${Html.escape(e.message ?: e::class.simpleName.orEmpty())}") }
                }
            }
        }
    }

    private suspend fun handle(message: TelegramMessage) {
        val text = message.text?.trim()?.takeIf { it.isNotEmpty() } ?: return
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

        if (!text.startsWith("/")) return converse(text)

        // В группах команда приходит как /fresh@имя_бота.
        val command = text.substringBefore(' ').substringBefore('@').lowercase()
        val argument = text.substringAfter(' ', "").trim()
        log.info("Команда {}", command)
        when (command) {
            "/start", "/help" -> reply(help())
            "/fresh" -> converse(
                if (argument.isEmpty()) "Собери свежую сводку Хабра" else "Собери свежую сводку Хабра по теме «$argument»",
            )
            "/latest" -> converse("Покажи последнюю сохранённую сводку")
            "/reset" -> {
                agent.reset()
                reply("🧹 Историю разговора забыл. Сохранённые отчёты остались на месте.")
            }
            else -> reply("🤔 Не знаю такой команды.\n\n${help()}")
        }
    }

    /** Один запрос к агенту: трассировка обновляется на каждом шаге, отчёт — отдельным сообщением. */
    private suspend fun converse(question: String) {
        val owner = config.ownerChatId!!
        log.info("Агенту: {}", question.take(200))
        val traceId = telegram.sendMessage(owner, "⏳ Думаю…")
        val lines = mutableListOf<String>()

        val turn = try {
            agent.ask(question) { step ->
                lines += handoff.describe(step)
                log.info("Шаг {}: {}", step.tool, lines.last().replace('\n', ' '))
                runCatching { telegram.editMessage(owner, traceId, trace(lines, working = true)) }
                    .onFailure { log.warn("Трассировка не обновлена: {}", it.message) }
            }
        } catch (e: LlmException) {
            telegram.editMessage(owner, traceId, trace(lines) + "\n\n⚠️ ${Html.escape(e.message.orEmpty())}")
            return
        }

        val final = trace(lines) + (if (lines.isEmpty()) "" else "\n\n") + "💬 ${Html.escape(turn.answer)}"
        if (final.length <= TelegramClient.MAX_MESSAGE_LENGTH) {
            telegram.editMessage(owner, traceId, final)
        } else {
            // Длинный ответ в одно сообщение не влезет: трассировка остаётся на месте, ответ идёт следом.
            telegram.editMessage(owner, traceId, trace(lines).ifEmpty { "💬" })
            reply(Html.escape(turn.answer))
        }

        turn.steps.lastOrNull { it.tool in REPORT_TOOLS && !it.outcome.isError }?.outcome?.structured
            ?.let { reply(ReportView.html(it)) }
    }

    private fun trace(lines: List<String>, working: Boolean = false): String {
        if (lines.isEmpty()) return if (working) "⏳ Думаю…" else ""
        return "<b>🛠 Конвейер</b>\n" + lines.joinToString("\n") { Html.escape(it) } + if (working) "\n⏳ …" else ""
    }

    private suspend fun reply(html: String) {
        telegram.sendMessage(config.ownerChatId!!, html)
    }

    private fun help(): String = """
        👋 Я агент дайджеста Хабра. Пиши обычным текстом — например, «собери свежую сводку»,
        «что пишут про RAG» или «покажи последнюю сводку».

        На сводку я сам прохожу три шага: 🔎 поиск → 🧠 сводка → 💾 сохранение, и под каждым шагом
        показываю, дошли ли данные до следующего без изменений.

        /fresh — свежая сводка, /fresh RAG — по теме
        /latest — последняя сохранённая сводка
        /reset — забыть историю разговора
    """.trimIndent()

    companion object {
        /** Меню команд у поля ввода в Telegram. */
        val COMMANDS = listOf(
            "fresh" to "Свежая сводка (или /fresh RAG — по теме)",
            "latest" to "Последняя сохранённая сводка",
            "reset" to "Забыть историю разговора",
            "help" to "Что умеет бот",
        )

        /** Инструменты, после которых бот сам показывает отчёт. */
        private val REPORT_TOOLS = setOf("report_save", "report_latest")
        private val RETRY_DELAY = 5.seconds
    }
}

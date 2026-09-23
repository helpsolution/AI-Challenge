package advent.news.agent

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Agent loop для чата. Один проход цикла — один поход к модели:
 *
 *   1. отдаём модели историю разговора и список инструментов;
 *   2. если модель ответила текстом — это ответ пользователю, цикл закончен;
 *   3. если модель попросила инструменты — вызываем их через MCP, кладём результаты
 *      в историю ролью "tool" и идём на следующий проход.
 */
class Agent(
    private val llm: DeepSeekClient,
    private val toolbox: McpToolbox,
    private val trace: Trace,
) {
    private val history = mutableListOf(Message(role = "system", content = Prompts.CHAT))

    suspend fun ask(question: String): String {
        // Агент живёт сутками, поэтому «сейчас» нельзя вписать в системный промпт один раз при старте:
        // без свежего времени модель не поймёт, сколько минут в «за утро» или «с обеда».
        history += Message(role = "user", content = "[сейчас ${NOW.format(ZonedDateTime.now())}] $question")
        try {
            return loop()
        } finally {
            shrinkToolResults()
        }
    }

    private suspend fun loop(): String {
        repeat(MAX_STEPS) {
            val exchange = llm.complete(history, toolbox.definitions)
            trace.llmExchange(exchange)
            val reply = exchange.message
            history += reply

            val calls = reply.toolCalls.orEmpty()
            if (calls.isEmpty()) {
                return reply.content?.takeIf { it.isNotBlank() } ?: "Модель ответила пустым сообщением"
            }

            for (call in calls) {
                trace.toolCall(call)
                val outcome = toolbox.call(call)
                trace.toolResult(outcome)
                // Результат обязан вернуться с тем же tool_call_id, иначе модель не поймёт,
                // на какой из своих вызовов смотрит.
                history += Message(role = "tool", toolCallId = call.id, content = outcome.text)
            }
        }

        // Ограничение на число проходов: без него модель, зациклившаяся на инструменте,
        // будет ходить по кругу за деньги пользователя.
        return "Не уложился в $MAX_STEPS шагов. Попробуйте сформулировать задачу проще."
    }

    /**
     * Сводка — это сотня заголовков, тысячи токенов. Агент работает 24/7, и если хранить каждую
     * целиком, история за день переполнит контекст модели. Ответ, собранный из сводки, в истории
     * остаётся, поэтому переспросить «подробнее про второй пункт» можно; сам сырой список уже не нужен.
     */
    private fun shrinkToolResults() {
        history.replaceAll { message ->
            val content = message.content
            if (message.role == "tool" && content != null && content.length > KEEP_TOOL_RESULT_CHARS) {
                message.copy(content = content.take(KEEP_TOOL_RESULT_CHARS) + "\n…(дальше сокращено: результат уже использован в ответе)")
            } else {
                message
            }
        }
    }

    private companion object {
        const val MAX_STEPS = 6
        const val KEEP_TOOL_RESULT_CHARS = 600
        val NOW: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm", Locale.forLanguageTag("ru"))
    }
}

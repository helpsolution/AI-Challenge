package advent.users.agent

/**
 * Agent loop. Один проход цикла — один поход к модели:
 *
 *   1. отдаём модели историю разговора и список инструментов;
 *   2. если модель ответила текстом — это ответ пользователю, цикл закончен;
 *   3. если модель попросила инструменты — вызываем их через MCP, кладём результаты
 *      в историю ролью "tool" и идём на следующий проход.
 *
 * Именно поэтому нужен цикл, а не один запрос: узнав результат инструмента,
 * модель может решить вызвать следующий — например, сначала find_user, потом create_user.
 */
class Agent(
    private val llm: DeepSeekClient,
    private val toolbox: McpToolbox,
    private val trace: Trace,
) {
    private val history = mutableListOf(Message(role = "system", content = SYSTEM_PROMPT))

    suspend fun ask(question: String): String {
        history += Message(role = "user", content = question)

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

    private companion object {
        const val MAX_STEPS = 6

        val SYSTEM_PROMPT = """
            Ты помощник по базе пользователей. Все данные берёшь только из инструментов —
            ничего не выдумывай и не отвечай по памяти.

            Как работать:
            - чтобы найти человека, вызывай find_user; точный email знать не нужно, хватит части имени;
            - чтобы завести человека, вызывай create_user; email уникален, повтор вернёт ошибку;
            - перед созданием разумно проверить через find_user, нет ли такого человека уже;
            - если инструмент вернул ошибку, объясни её пользователю по-человечески и предложи,
              что сделать дальше, вместо повторного вызова с теми же аргументами.

            Отвечай коротко, по-русски, без придуманных подробностей.
        """.trimIndent()
    }
}

package advent.pipeline.bot

import kotlinx.serialization.json.JsonObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Один вызов инструмента: что попросила модель и что вернул MCP. */
data class Step(
    val tool: String,
    /** null — модель прислала аргументы, которые не разбираются как JSON-объект. */
    val arguments: JsonObject?,
    val outcome: ToolOutcome,
)

/** Итог одного сообщения пользователя: ответ модели и все вызовы инструментов по дороге. */
data class Turn(val answer: String, val steps: List<Step>)

/**
 * Agent loop. Один проход цикла — один поход к модели:
 *
 *   1. отдаём модели историю разговора и список инструментов;
 *   2. если модель ответила текстом — это ответ пользователю, цикл закончен;
 *   3. если модель попросила инструменты — вызываем их через MCP, кладём результаты
 *      в историю ролью "tool" и идём на следующий проход.
 *
 * Цепочку search → summarize → save здесь никто не прописывает: её собирает модель,
 * перекладывая результат одного инструмента в аргументы следующего.
 */
class Agent(
    private val llm: DeepSeekClient,
    private val toolbox: McpToolbox,
) {
    private val system = Message(role = "system", content = Prompts.AGENT)

    /** Прошлые сообщения: вопрос и ответ, без промежуточных вызовов. */
    private val history = mutableListOf<Message>()

    suspend fun ask(question: String, onStep: suspend (Step) -> Unit = {}): Turn {
        // Бот живёт сутками, поэтому «сейчас» нельзя вписать в системный промпт один раз при старте.
        val turn = mutableListOf(Message(role = "user", content = "[сейчас ${NOW.format(ZonedDateTime.now())}] $question"))
        val steps = mutableListOf<Step>()

        repeat(MAX_STEPS) {
            val reply = llm.complete(listOf(system) + history + turn, toolbox.definitions)
            turn += reply

            val calls = reply.toolCalls.orEmpty()
            if (calls.isEmpty()) {
                val answer = reply.content?.trim()?.takeIf { it.isNotEmpty() } ?: "Готово."
                remember(question, answer, steps)
                return Turn(answer, steps)
            }

            for (call in calls) {
                val arguments = McpToolbox.parseArguments(call.function.arguments)
                val step = Step(call.function.name, arguments, toolbox.call(call.function.name, arguments))
                steps += step
                onStep(step)
                // Результат обязан вернуться с тем же tool_call_id, иначе модель не поймёт,
                // на какой из своих вызовов смотрит.
                turn += Message(role = "tool", toolCallId = call.id, content = step.outcome.text)
            }
        }

        // Ограничение на число проходов: без него модель, зациклившаяся на инструменте,
        // будет ходить по кругу за деньги пользователя.
        val answer = "Не уложился в $MAX_STEPS шагов. Попробуйте сформулировать задачу проще."
        remember(question, answer, steps)
        return Turn(answer, steps)
    }

    fun reset() = history.clear()

    /**
     * В историю идут только вопрос и ответ. Статьи и сводка — это тысячи токенов в аргументах и результатах,
     * и хранить их между сообщениями незачем: следующий «собери сводку» начнёт с нового поиска,
     * а за прошлым отчётом модель сходит в report_latest. Какие инструменты были вызваны, модель
     * при этом помнит — по короткой пометке перед ответом.
     */
    private fun remember(question: String, answer: String, steps: List<Step>) {
        val note = steps.takeIf { it.isNotEmpty() }
            ?.joinToString(" → ", prefix = "(вызваны инструменты: ", postfix = ")\n") { it.tool + if (it.outcome.isError) " — ошибка" else "" }
            .orEmpty()
        history += Message(role = "user", content = question)
        history += Message(role = "assistant", content = note + answer)
        while (history.size > MAX_HISTORY) history.removeFirst()
    }

    private companion object {
        const val MAX_STEPS = 8
        const val MAX_HISTORY = 20
        val NOW: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm", Locale.forLanguageTag("ru"))
    }
}

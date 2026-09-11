package advent.day10.context

import advent.day10.chat.Role
import advent.day10.chat.StrategyId
import advent.day10.llm.ApiMessage
import org.slf4j.LoggerFactory

/**
 * Стратегия 1: последние N сообщений, остальное отбрасывается.
 *
 * Самая простая из трёх и потому самая честная точка отсчёта: ни одного обращения
 * к модели сверх самого хода, ни строчки состояния, размер промпта ограничен сверху
 * и перестаёт расти. Всё, чем за это заплачено, — начало разговора модель не видит вовсе.
 *
 * Обслуживания у неё нет: `observe` не переопределён, потому что тратить нечего —
 * и в таблице сравнения графа «обслуживание памяти» у окна остаётся пустой.
 *
 * Размер окна берётся из сессии, а не из конфига: он записан туда при создании и уже
 * не меняется. Иначе правка `application.yml` задним числом переписала бы условия
 * прогонов, которые уже лежат в базе.
 */
class SlidingWindowStrategy(override val id: StrategyId = StrategyId.SLIDING_WINDOW) : ContextStrategy {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun assemble(input: StrategyInput): PromptContext {
        val window = window(input)

        val blocks = buildList {
            input.persona.takeIf { it.isNotBlank() }?.let { add(ApiMessage("system", it)) }
            window.forEach { add(ApiMessage(it.role.name.lowercase(), it.content)) }
            add(ApiMessage("user", input.question))
        }

        val dropped = input.history.size - window.size
        if (dropped > 0) {
            log.debug(
                "Окно {}: из {} сообщений истории отправлено {}, отброшено {}",
                input.session.windowSize, input.history.size, window.size, dropped,
            )
        }

        return PromptContext(
            blocks = blocks,
            includedMessages = window.size,
            note = if (dropped > 0) "окно ${input.session.windowSize}, отброшено $dropped" else null,
        )
    }

    /**
     * Последние N сообщений, но начиная с реплики пользователя.
     *
     * Выравнивание нужно вот зачем: если окно начинается с ответа ассистента, модель
     * видит свою реплику без вопроса, на который та отвечала. Это не ошибка протокола —
     * запрос уйдёт и ответ придёт, — но модель принимает висящий ответ за начало разговора
     * и часто переспрашивает то, что уже обсуждалось. Ценой одного сообщения окно
     * становится осмысленным куском диалога, а не произвольным срезом.
     *
     * В обычном диалоге срез и так попадает на пользователя: реплики чередуются, а N чётное.
     * Выравнивание срабатывает после неудачного хода и в ветке, где пар может быть нечётное
     * число, — то есть именно там, где ошибиться легче всего.
     */
    private fun window(input: StrategyInput) = input.history
        .takeLast(input.session.windowSize)
        .dropWhile { it.role == Role.ASSISTANT }
}

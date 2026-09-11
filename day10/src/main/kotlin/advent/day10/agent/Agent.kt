package advent.day10.agent

import advent.day10.chat.Exchange
import advent.day10.chat.Session
import advent.day10.chat.StrategyId
import advent.day10.chat.Turn
import advent.day10.context.PromptContext
import advent.day10.context.StrategyInput
import advent.day10.context.StrategyRegistry
import advent.day10.llm.ChatCompletionRequest
import advent.day10.llm.LlmClient
import advent.day10.llm.LlmCompletion
import advent.day10.llm.LlmException
import advent.day10.store.ChatStore
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * Агент — основной кубик приложения.
 *
 * Всё, что он делает, — четыре шага в неизменном порядке: спросить стратегию о промпте,
 * сходить в модель, записать ход, дать стратегии обновить своё состояние. Ни одного
 * условия «если стратегия такая-то» здесь нет и быть не должно: как только оно появится,
 * добавление четвёртой стратегии перестанет быть локальным изменением.
 *
 * В дне 9 этот класс разросся до 532 строк ровно потому, что держал в себе и сборку
 * контекста, и суммаризацию, и учёт токенов. Здесь сборка живёт в стратегии контекста,
 * хранение — в [ChatStore], а агенту остаётся последовательность и измерение.
 *
 * Класс не знает ни про Spring, ни про HTTP, ни про SQL — только четыре контракта.
 */
class Agent(
    private val llm: LlmClient,
    private val store: ChatStore,
    private val strategies: StrategyRegistry,
    val config: AgentConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Новый диалог. Стратегия и размер окна фиксируются здесь и дальше не меняются:
     * переключатель в интерфейсе создаёт сессию, а не переключает режим существующей.
     *
     * Причина не в удобстве реализации, а в измерении: в сессии со сменой режима часть
     * ходов прошла по одним правилам, часть по другим, и сравнивать её не с чем.
     */
    fun createSession(title: String?, strategy: StrategyId?, windowSize: Int?): Session {
        val chosen = strategy ?: config.defaultStrategy
        val size = windowSize ?: config.defaultWindowSize
        require(size >= AgentConfig.MIN_WINDOW) {
            "Окно меньше ${AgentConfig.MIN_WINDOW} сообщений бессмысленно"
        }
        // Проверяем до создания: сессия с нереализованной стратегией была бы мёртвой,
        // и узнал бы об этом пользователь только на первом вопросе.
        strategies.require(chosen)

        val name = title?.trim()?.takeIf { it.isNotEmpty() } ?: defaultTitle(chosen, size)
        return store.createSession(name, chosen, size).also {
            log.info("Новая сессия {}: стратегия {}, окно {}", it.id, chosen, size)
        }
    }

    /**
     * Один ход: вопрос внутрь, ответ и его цена наружу.
     *
     * Порядок важен: переписку пишем **после** ответа модели. Если модель не ответила,
     * история не меняется — ход как будто не состоялся. Но сам факт хода сохраняется:
     * неудача попадает в таблицу расхода с текстом ошибки.
     */
    fun ask(sessionId: Long, question: String): Exchange {
        val text = question.trim()
        require(text.isNotEmpty()) { "Пустое сообщение — отвечать нечего" }

        val session = store.requireSession(sessionId)
        val strategy = strategies.require(session.strategy)
        val history = store.history(sessionId)
        // Номер хода — по числу измеренных ходов, а не по длине истории: неудачный ход
        // сообщений не добавляет, но состоялся, и свой номер занимает.
        val number = store.turns(sessionId).size + 1

        val input = StrategyInput(session, number, config.persona, history, text)
        val context = strategy.assemble(input)

        log.info(
            "Сессия {} · ход {} · {}: история {} сообщений, в промпт вошло {}, отброшено {}, символов {}",
            sessionId, number, session.strategy, history.size, context.includedMessages,
            history.size - context.includedMessages, context.charsSent,
        )
        log.debug("\n{}", promptDump(number, text, context))

        val startedNanos = System.nanoTime()
        val completion = try {
            llm.complete(
                ChatCompletionRequest(
                    model = config.model,
                    messages = context.blocks,
                    temperature = config.temperature,
                    maxTokens = config.maxTokens,
                ),
            )
        } catch (e: LlmException) {
            val failed = turn(number, session, history.size, context, elapsedMs(startedNanos))
                .copy(error = e.message, errorStatus = e.providerStatus)
            store.saveFailedTurn(failed)
            log.warn(
                "Сессия {} · ход {} не удался за {} мс: {}. Ход записан в расход, история не изменилась",
                sessionId, number, failed.latencyMs, e.message,
            )
            throw e
        }
        val latencyMs = elapsedMs(startedNanos)

        log.debug("\n{}", answerDump(number, completion, latencyMs))

        val measured = turn(number, session, history.size, context, latencyMs).withUsage(completion)
        val exchange = store.saveTurn(sessionId, text, completion.content, measured)

        log.info(
            "Сессия {} · ход {}: ответ за {} мс. Токены: запрос {} (из кэша {}), ответ {}. Цена: {}",
            sessionId, number, latencyMs, measured.promptTokens ?: 0, measured.cachedPromptTokens ?: 0,
            measured.completionTokens ?: 0, measured.costUsd?.let { "$%.6f".format(it) } ?: "неизвестна",
        )

        // Ход состоялся и записан — только теперь стратегии есть что наблюдать.
        //
        // Всё, что она на этом потратила, попадает в базу отдельной строкой. Иначе
        // стратегия с дорогой памятью выглядела бы в сравнении дешевле той, у которой
        // памяти нет вовсе, — а счёт от провайдера говорил бы обратное.
        val upkeep = strategy.observe(input, exchange.answer)
        if (upkeep == null) return exchange

        store.saveUpkeep(sessionId, number, upkeep)
        log.info(
            "Сессия {} · ход {}: обслуживание памяти за {} мс — {}. Токены: {}, цена: {}",
            sessionId, number, upkeep.latencyMs, upkeep.note, upkeep.totalTokens ?: 0,
            upkeep.costUsd?.let { "$%.6f".format(it) } ?: "неизвестна",
        )
        return exchange.copy(turn = measured.copy(upkeep = upkeep))
    }

    private fun turn(number: Int, session: Session, historySize: Int, context: PromptContext, latencyMs: Long) = Turn(
        number = number,
        at = clock.instant(),
        sessionId = session.id,
        strategy = session.strategy,
        model = config.model,
        historyMessages = historySize,
        includedMessages = context.includedMessages,
        promptBlocks = context.blocks.size,
        charsSent = context.charsSent,
        note = context.note,
        latencyMs = latencyMs,
    )

    private fun Turn.withUsage(completion: LlmCompletion): Turn {
        val usage = completion.usage ?: return copy(
            model = completion.model ?: model,
            finishReason = completion.finishReason,
        )
        return copy(
            model = completion.model ?: model,
            promptTokens = usage.promptTokens,
            cachedPromptTokens = usage.cachedPromptTokens,
            completionTokens = usage.completionTokens,
            totalTokens = usage.totalTokens,
            costUsd = usage.costUsd,
            costSource = usage.costSource,
            finishReason = completion.finishReason,
        )
    }

    private fun defaultTitle(strategy: StrategyId, windowSize: Int) = when (strategy) {
        StrategyId.SLIDING_WINDOW -> "Скользящее окно $windowSize"
        StrategyId.FACTS -> "Факты + окно $windowSize"
        StrategyId.BRANCHING -> "Ветвление, окно $windowSize"
    }

    private fun elapsedMs(startedNanos: Long) = (System.nanoTime() - startedNanos) / 1_000_000

    /**
     * Что пришло агенту на вход и во что стратегия это превратила — блок за блоком.
     *
     * В этом дне дамп перестал быть отладочной роскошью: именно здесь глазами видно,
     * что окно выбросило начало разговора. Числа в отчёте говорят «отброшено 8
     * сообщений», а дамп показывает, каких именно, — и без него разбираться, почему
     * агент забыл дедлайн, пришлось бы гаданием.
     */
    private fun promptDump(number: Int, input: String, context: PromptContext): String = buildString {
        appendLine("┌── ХОД $number · вход агента (${input.length} симв.)")
        input.lines().forEach { appendLine("│  $it") }
        appendLine("├── ПРОМПТ · ${context.blocks.size} блоков, ${context.charsSent} симв.${context.note?.let { " · $it" } ?: ""}")
        context.blocks.forEachIndexed { i, block ->
            appendLine("│  [${i + 1}] ${block.role.uppercase()} (${block.content.length} симв.)")
            block.content.lines().forEach { appendLine("│       $it") }
        }
        append(
            "└── уходит в ${config.model}: temperature ${config.temperature}, " +
                "потолок ответа ${config.maxTokens} токенов",
        )
    }

    /** Что вернула модель: текст, расход токенов, причина остановки. */
    private fun answerDump(number: Int, completion: LlmCompletion, latencyMs: Long): String = buildString {
        appendLine("┌── ХОД $number · ответ модели за $latencyMs мс (${completion.content.length} симв.)")
        completion.content.lines().forEach { appendLine("│  $it") }
        completion.usage?.let { usage ->
            appendLine(
                "├── токены: вход ${usage.promptTokens}" +
                    (usage.cachedPromptTokens.takeIf { it > 0 }?.let { " (из них из кэша $it)" } ?: "") +
                    ", выход ${usage.completionTokens}, всего ${usage.totalTokens}",
            )
            usage.costUsd?.let { appendLine("├── цена хода: $${"%.6f".format(it)} (${usage.costSource})") }
        }
        append("└── модель ${completion.model ?: config.model}, остановка: ${completion.finishReason ?: "не указана"}")
    }
}

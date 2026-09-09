package advent.day8.agent

import advent.day8.chat.Message
import advent.day8.chat.Role
import advent.day8.chat.Turn
import advent.day8.llm.ApiMessage
import advent.day8.llm.ChatCompletionRequest
import advent.day8.llm.LlmClient
import advent.day8.llm.LlmCompletion
import advent.day8.llm.LlmException
import advent.day8.llm.TokenUsage
import advent.day8.store.ChatStore
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * Агент, который помнит разговор и знает его цену.
 *
 * Модель беспамятна по своей природе: она знает ровно то, что лежит в `messages` текущего
 * запроса. Поэтому «помнить» — это на каждом ходу заново собрать всю переписку и отправить
 * её вместе с новым вопросом. Отсюда и тема этого дня: если история уходит целиком каждый
 * ход, то с каждым ходом растёт и запрос, и его цена, и расстояние до лимита модели.
 *
 * Своего токенизатора здесь нет и не нужно: настоящий счётчик приходит в каждом ответе
 * провайдера. Оценка была бы хуже факта ровно тем, что выглядела бы так же убедительно.
 *
 * Класс не знает ни про Spring, ни про HTTP, ни про SQL — только два контракта.
 */
class Agent(
    private val llm: LlmClient,
    private val store: ChatStore,
    val settings: AgentSettings,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Ответ и его цена: контроллеру нужно и то и другое. */
    data class Exchange(val answer: Message, val turn: Turn)

    fun history(): List<Message> = store.history()

    fun remembered(): Int = store.count()

    fun turns(): List<Turn> = store.turns()

    /**
     * Один ход: вопрос внутрь, ответ и его цена наружу.
     *
     * Порядок важен: переписку пишем **после** ответа модели, а не до. Если модель
     * не ответила, история не меняется — ход как будто не состоялся. Но сам факт хода
     * теперь сохраняется даже тогда: неудача попадает в таблицу расхода с текстом ошибки,
     * потому что «упёрлись в лимит» — то, что этот день должен показать, а не проглотить.
     */
    fun ask(question: String): Exchange {
        val text = question.trim()
        require(text.isNotEmpty()) { "Пустое сообщение — отвечать нечего" }

        val history = store.history()
        val previous = store.turns()
        // Номер хода — по числу измеренных ходов, а не по длине истории: неудачный ход
        // сообщений не добавляет, но состоялся, и свой номер занимает.
        val number = previous.size + 1
        val prompt = buildPrompt(history, text)
        val charsSent = prompt.sumOf { it.content.length }

        log.info(
            "Ход {}: сообщений в истории: {}, блоков в промпте: {}, символов: {}",
            number, history.size, prompt.size, charsSent,
        )
        log.debug("\n{}", promptDump(number, text, prompt))

        val startedNanos = System.nanoTime()
        val completion = try {
            llm.complete(
                ChatCompletionRequest(
                    model = settings.model,
                    messages = prompt,
                    temperature = settings.temperature,
                    maxTokens = settings.maxTokens,
                ),
            )
        } catch (e: LlmException) {
            val failed = failedTurn(number, history, prompt, charsSent, elapsedMs(startedNanos), e)
            store.saveFailedTurn(failed)
            log.warn(
                "Ход {} не удался за {} мс: {}. Ход записан в расход, история не изменилась",
                number, failed.latencyMs, e.message,
            )
            throw e
        }
        val latencyMs = elapsedMs(startedNanos)

        log.debug("\n{}", answerDump(number, completion, latencyMs))

        val turn = successfulTurn(
            number = number,
            history = history,
            prompt = prompt,
            charsSent = charsSent,
            completion = completion,
            latencyMs = latencyMs,
            previousTurns = previous,
        )

        val asked = Message(Role.USER, text, clock.instant())
        val answered = Message(Role.ASSISTANT, completion.content, clock.instant())
        store.saveTurn(asked, answered, turn)

        log.info(
            "Ход {}: ответ за {} мс. Токены: запрос {}, ответ {}, всего {} — это {}% контекста под промпт ({}). Цена хода: {}",
            number, latencyMs, turn.promptTokens ?: 0, turn.completionTokens ?: 0, turn.totalTokens ?: 0,
            turn.promptTokens?.let { it * 100 / settings.contextForPrompt.coerceAtLeast(1) } ?: 0,
            settings.contextForPrompt, turn.costUsd?.let { "$%.6f".format(it) } ?: "неизвестна",
        )
        if (turn.compressed) {
            log.warn(
                "Ход {}: промпт дошёл до модели урезанным. Отправлено {} символов, провайдер насчитал {} токенов " +
                    "({} симв. на токен) — ошибки не было, но часть истории модель не увидела",
                number, charsSent, turn.promptTokens, "%.1f".format(turn.charsPerToken ?: 0.0),
            )
        }
        return Exchange(answered, turn)
    }

    fun forget() {
        store.clear()
        log.info("Диалог стёрт вместе с расходом: {} начинает разговор с чистого листа", settings.name)
    }

    /**
     * Персона, затем вся переписка, затем новый вопрос.
     *
     * Историю отправляем целиком, без окна: задание дня 7 требовало, чтобы агент помнил
     * прошлые сообщения, и урезанное окно этому противоречило бы. Цена такого решения —
     * ровно то, что измеряет этот день.
     */
    private fun buildPrompt(history: List<Message>, question: String): List<ApiMessage> = buildList {
        settings.persona.takeIf { it.isNotBlank() }?.let { add(ApiMessage("system", it)) }
        history.forEach { add(ApiMessage(it.role.name.lowercase(), it.content)) }
        add(ApiMessage("user", question))
    }

    private fun successfulTurn(
        number: Int,
        history: List<Message>,
        prompt: List<ApiMessage>,
        charsSent: Int,
        completion: LlmCompletion,
        latencyMs: Long,
        previousTurns: List<Turn>,
    ): Turn {
        val usage: TokenUsage? = completion.usage
        return Turn(
            number = number,
            at = clock.instant(),
            provider = completion.provider ?: "неизвестен",
            model = completion.model ?: settings.model,
            contextLimit = settings.contextLimit,
            maxTokens = settings.maxTokens,
            historyMessages = history.size,
            promptBlocks = prompt.size,
            charsSent = charsSent,
            promptTokens = usage?.promptTokens,
            cachedPromptTokens = usage?.cachedPromptTokens,
            completionTokens = usage?.completionTokens,
            totalTokens = usage?.totalTokens,
            costUsd = usage?.costUsd,
            costSource = usage?.costSource,
            latencyMs = latencyMs,
            finishReason = completion.finishReason,
            compressed = compressionSuspected(previousTurns, charsSent, usage?.promptTokens),
        )
    }

    private fun failedTurn(
        number: Int,
        history: List<Message>,
        prompt: List<ApiMessage>,
        charsSent: Int,
        latencyMs: Long,
        error: LlmException,
    ) = Turn(
        number = number,
        at = clock.instant(),
        provider = "—",
        model = settings.model,
        contextLimit = settings.contextLimit,
        maxTokens = settings.maxTokens,
        historyMessages = history.size,
        promptBlocks = prompt.size,
        charsSent = charsSent,
        latencyMs = latencyMs,
        error = error.message,
        errorStatus = error.providerStatus,
    )

    /**
     * Дошёл ли промпт до модели целиком.
     *
     * Прямого ответа провайдер не даёт: при сжатии контекста он отвечает `200`
     * и `finish_reason: stop`, как при обычном ходе. Единственный след — счётчик токенов,
     * и читать его надо аккуратно.
     *
     * Идея проверки: **прирост токенов обязан соответствовать приросту текста.** Сколько
     * символов добавилось к промпту с прошлого хода, мы знаем точно — считаем сами перед
     * отправкой. Во сколько токенов обходится символ в этом конкретном диалоге, мы тоже
     * знаем: это измеренная плотность его же неурезанных ходов. Значит ожидаемый прирост
     * токенов вычисляется, и если фактический заметно ниже — часть промпта до модели
     * не дошла.
     *
     * Наивные проверки здесь не работают, это выяснилось на живых числах:
     *
     * - «токены перестали расти» ловит только сам переход в урезанный режим. Дальше
     *   каждый ход добавляет немного текста, токены понемногу растут, и признак гаснет,
     *   хотя историю продолжают резать.
     * - «слишком много символов на токен» требует абсолютного порога, а разница
     *   оказалась мала: 3.33 симв./токен на целом промпте против 4.46 на урезанном.
     *   Любой порог между ними — подгонка под один диалог.
     *
     * Плотность берём минимальную среди неурезанных ходов: урезание её только завышает,
     * поэтому минимум — самая честная точка отсчёта.
     */
    private fun compressionSuspected(previous: List<Turn>, charsSent: Int, promptTokens: Int?): Boolean {
        if (promptTokens == null || promptTokens <= 0) return false

        val measured = previous.filter { it.error == null && !it.compressed }
        val last = measured.lastOrNull()
        val density = measured.mapNotNull { it.charsPerToken }.minOrNull()

        // Первый ход: сравнивать не с чем, плотность этого диалога ещё не измерена.
        // Остаётся грубый предел правдоподобия — он поймает только явное урезание.
        if (last == null || density == null) {
            return charsSent.toDouble() / promptTokens > MAX_PLAUSIBLE_CHARS_PER_TOKEN
        }

        val addedChars = charsSent - last.charsSent
        // Совсем маленькая добавка ничего не докажет: там всё в пределах погрешности.
        if (addedChars < MIN_MEANINGFUL_CHARS) return false

        val expectedGrowth = addedChars / density
        val actualGrowth = promptTokens - (last.promptTokens ?: return false)
        return actualGrowth < expectedGrowth * GROWTH_TOLERANCE
    }

    private fun elapsedMs(startedNanos: Long) = (System.nanoTime() - startedNanos) / 1_000_000

    /**
     * Что пришло агенту на вход и во что он это превратил — блок за блоком, целиком.
     *
     * Промпт стоит видеть глазами: именно здесь память из базы превращается в контекст
     * запроса, и именно здесь видно, как он растёт с каждым ходом. Поэтому уровень DEBUG,
     * а не TRACE: включён по умолчанию, гасится одной строкой конфига.
     */
    private fun promptDump(number: Int, input: String, prompt: List<ApiMessage>): String = buildString {
        appendLine("┌── ХОД $number · вход агента (${input.length} симв.)")
        input.lines().forEach { appendLine("│  $it") }
        appendLine("├── ПРОМПТ МОДЕЛИ · ${prompt.size} блоков, ${prompt.sumOf { it.content.length }} симв.")
        prompt.forEachIndexed { i, block ->
            appendLine("│  [${i + 1}] ${block.role.uppercase()} (${block.content.length} симв.)")
            block.content.lines().forEach { appendLine("│       $it") }
        }
        append(
            "└── уходит в ${settings.model}: temperature ${settings.temperature}, " +
                "потолок ответа ${settings.maxTokens} токенов, лимит контекста ${settings.contextLimit}",
        )
    }

    /** Что вернула модель: текст, расход токенов, причина остановки. */
    private fun answerDump(number: Int, completion: LlmCompletion, latencyMs: Long): String = buildString {
        appendLine("┌── ХОД $number · ответ модели за $latencyMs мс (${completion.content.length} симв.)")
        completion.content.lines().forEach { appendLine("│  $it") }
        completion.reasoning?.let { reasoning ->
            appendLine("├── скрытое рассуждение (${reasoning.length} симв.)")
            reasoning.lines().forEach { appendLine("│  $it") }
        }
        completion.usage?.let { usage ->
            appendLine(
                "├── токены: вход ${usage.promptTokens}" +
                    (usage.cachedPromptTokens.takeIf { it > 0 }?.let { " (из них из кэша провайдера $it)" } ?: "") +
                    ", выход ${usage.completionTokens}" +
                    (usage.reasoningTokens.takeIf { it > 0 }?.let { " (из них на рассуждение $it)" } ?: "") +
                    ", всего ${usage.totalTokens}",
            )
            usage.costUsd?.let { cost ->
                appendLine("├── цена хода: $${"%.6f".format(cost)} (${usage.costSource})")
            }
        }
        append(
            "└── модель ${completion.model ?: settings.model} на площадке ${completion.provider ?: "—"}, " +
                "остановка: ${completion.finishReason ?: "не указана"}",
        )
    }

    private companion object {
        /**
         * Предел правдоподобия для самого первого хода, когда плотность диалога ещё
         * не измерена. Русская фраза — это 2–4 символа на токен, английская около 4;
         * всё, что вдвое выше, означает, что посчитали не тот текст, который мы отправили.
         */
        const val MAX_PLAUSIBLE_CHARS_PER_TOKEN = 8.0

        /** Какую долю ожидаемого прироста считаем нормой. Ниже — промпт урезали. */
        const val GROWTH_TOLERANCE = 0.6

        /** Меньшая добавка к промпту ничего не доказывает: разница утонет в погрешности. */
        const val MIN_MEANINGFUL_CHARS = 50
    }
}

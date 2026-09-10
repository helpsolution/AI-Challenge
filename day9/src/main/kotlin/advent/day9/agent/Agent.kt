package advent.day9.agent

import advent.day9.chat.Message
import advent.day9.chat.Role
import advent.day9.chat.Summary
import advent.day9.chat.Turn
import advent.day9.chat.TurnKind
import advent.day9.context.ContextAssembler
import advent.day9.context.ContextMode
import advent.day9.context.PromptContext
import advent.day9.context.Summarizer
import advent.day9.llm.ChatCompletionRequest
import advent.day9.llm.LlmClient
import advent.day9.llm.LlmCompletion
import advent.day9.llm.LlmException
import advent.day9.llm.TokenUsage
import advent.day9.store.ChatStore
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * Агент, который помнит разговор, знает его цену и умеет её сбивать.
 *
 * Модель беспамятна: она знает ровно то, что лежит в `messages` текущего запроса. День 7
 * сделал из этого память — отправлять всю переписку каждый ход. День 8 показал цену такой
 * памяти: запрос растёт с каждым ходом, вместе с ним растут деньги и приближается лимит.
 * Этот день меняет саму подмену — модели уходит не вся переписка, а последние сообщения
 * дословно плюс конспект всего остального.
 *
 * Сборкой промпта агент больше не занимается сам: она уехала в [ContextAssembler], а сжатие —
 * в [Summarizer]. Агенту осталось то, что и должно быть его делом: провести ход, измерить
 * его и решить, не пора ли свернуть историю.
 *
 * Порядок в этом решении принципиален. Сжатие идёт **после** ответа, а не перед ним:
 * иначе раз в десяток ходов пользователь ждал бы два обращения к модели вместо одного,
 * и виноват был бы ход, который ничем от других не отличается. Плата за такой порядок —
 * один ход из пачки уходит модели с хвостом длиннее обещанного, и это видно в числах.
 *
 * Класс не знает ни про Spring, ни про HTTP, ни про SQL — только про свои контракты.
 */
class Agent(
    private val llm: LlmClient,
    private val store: ChatStore,
    val settings: AgentSettings,
    private val summarizer: Summarizer,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val policy = settings.context
    private val assembler = ContextAssembler(settings.persona)

    /** Ответ, его цена и сворачивания, случившиеся на этом ходу. */
    data class Exchange(val answer: Message, val turn: Turn, val folds: List<Turn>)

    fun history(): List<Message> = store.history()

    fun remembered(): Int = store.count()

    fun turns(): List<Turn> = store.turns()

    /**
     * Актуальный конспект — или null, если его нет.
     *
     * В режиме RAW возвращается null даже тогда, когда в базе лежат конспекты прошлых
     * прогонов: интерфейс по этому значению решает, какие сообщения модель видит дословно,
     * а в RAW она видит дословно все.
     */
    fun summary(): Summary? = if (policy.mode == ContextMode.SUMMARY) store.latestSummary() else null

    /** Все версии конспекта: по ним видно, как пересказ пересказа теряет подробности. */
    fun summaries(): List<Summary> = store.summaries()

    /**
     * Один ход: вопрос внутрь, ответ и его цена наружу.
     *
     * Порядок важен: переписку пишем **после** ответа модели, а не до. Если модель
     * не ответила, история не меняется — ход как будто не состоялся. Но сам факт хода
     * сохраняется: неудача попадает в таблицу расхода с текстом ошибки, потому что
     * «упёрлись в лимит» — то, что надо показать, а не проглотить.
     */
    fun ask(question: String): Exchange {
        val text = question.trim()
        require(text.isNotEmpty()) { "Пустое сообщение — отвечать нечего" }

        val previous = store.turns()
        // Номер хода — по числу ходов диалога, а не по числу обращений к модели: сворачивания
        // тоже обращения, но своего номера в разговоре у них нет.
        val number = previous.count { it.kind == TurnKind.ANSWER } + 1
        val context = buildContext(text)

        log.info(
            "Ход {} [{}]: в базе {} сообщений, дословно уходит {}, {} свёрнуто в конспект{}. " +
                "Промпт: {} блоков, {} симв. вместо {} симв. полной истории",
            number, policy.mode, context.historyTotal, context.promptMessages, context.foldedMessages,
            context.summaryVersion?.let { " (версия $it)" } ?: "",
            context.blocks.size, context.charsSent, context.rawChars,
        )
        log.debug("\n{}", promptDump(number, text, context))

        val startedNanos = System.nanoTime()
        val completion = try {
            llm.complete(
                ChatCompletionRequest(
                    model = settings.model,
                    messages = context.blocks,
                    temperature = settings.temperature,
                    maxTokens = settings.maxTokens,
                ),
            )
        } catch (e: LlmException) {
            val failed = failedTurn(number, context, elapsedMs(startedNanos), e)
            store.saveFailedTurn(failed)
            log.warn(
                "Ход {} не удался за {} мс: {}. Ход записан в расход, история не изменилась",
                number, failed.latencyMs, e.message,
            )
            throw e
        }
        val latencyMs = elapsedMs(startedNanos)

        log.debug("\n{}", answerDump(number, completion, latencyMs))

        val turn = successfulTurn(number, context, completion, latencyMs, previous)
        val asked = Message(Role.USER, text, clock.instant())
        val answered = Message(Role.ASSISTANT, completion.content, clock.instant())
        store.saveTurn(asked, answered, turn)

        log.info(
            "Ход {}: ответ за {} мс. Токены: запрос {}, ответ {}, всего {} — это {}% контекста " +
                "под промпт ({}). Цена хода: {}",
            number, latencyMs, turn.promptTokens ?: 0, turn.completionTokens ?: 0, turn.totalTokens ?: 0,
            turn.promptTokens?.let { it * 100 / settings.contextForPrompt.coerceAtLeast(1) } ?: 0,
            settings.contextForPrompt, turn.costUsd?.let { "$%.6f".format(it) } ?: "неизвестна",
        )
        if (turn.truncated) {
            log.warn(
                "Ход {}: промпт дошёл до модели урезанным — и урезали его не мы. Отправлено {} симв., " +
                    "провайдер насчитал {} токенов ({} симв. на токен). Ошибки не было, " +
                    "но часть контекста модель не увидела",
                number, turn.charsSent, turn.promptTokens, "%.1f".format(turn.charsPerToken ?: 0.0),
            )
        }

        return Exchange(answered, turn, foldHistory(number))
    }

    fun forget() {
        store.clear()
        log.info("Диалог стёрт вместе с конспектами и расходом: {} начинает с чистого листа", settings.name)
    }

    /**
     * Что уходит модели на этом ходу.
     *
     * В режиме RAW конспект не читается вовсе и хвост — вся переписка: получается ровно
     * то же поведение, что в дне 8, тем же кодом, а не второй веткой. В режиме SUMMARY
     * дословно уходит всё, что лежит за границей последнего конспекта.
     */
    private fun buildContext(question: String): PromptContext {
        val summary = summary()
        val tail = if (summary == null) store.history() else store.messagesAfter(summary.coversUptoMessageId)
        return assembler.assemble(
            tail = tail,
            summary = summary,
            question = question,
            historyTotal = store.count(),
            historyChars = store.chars(),
        )
    }

    /**
     * Сворачивание истории — работа после ответа, пользователь её не ждёт.
     *
     * Цикл, а не одно сворачивание: хвост может оказаться длиннее пачки сразу — например,
     * если прогон начался с базы, накопленной в режиме RAW. Каждый проход двигает границу
     * конспекта вперёд, поэтому цикл конечен.
     *
     * Неудача сворачивания намеренно не роняет ход: ответ пользователю уже отдан, а история
     * в базе цела — сжатие ничего не удаляет. Записываем неудачу в расход, выходим и пробуем
     * на следующем ходу.
     */
    private fun foldHistory(turnNumber: Int): List<Turn> {
        if (policy.mode != ContextMode.SUMMARY) return emptyList()

        val folds = mutableListOf<Turn>()
        while (true) {
            val previous = store.latestSummary()
            val tail = if (previous == null) store.history() else store.messagesAfter(previous.coversUptoMessageId)
            if (!policy.shouldFold(tail.size)) break

            val batch = tail.take(policy.summarizeEvery)
            val startedNanos = System.nanoTime()
            val folded = try {
                summarizer.fold(previous, batch)
            } catch (e: LlmException) {
                val failed = failedFold(turnNumber, previous, batch, elapsedMs(startedNanos), e)
                store.saveFailedTurn(failed)
                log.warn(
                    "Сжатие после хода {} не удалось: {}. История цела, конспект остался версии {} — " +
                        "попробуем на следующем ходу",
                    turnNumber, e.message, previous?.version ?: 0,
                )
                break
            }

            val version = (previous?.version ?: 0) + 1
            val summary = Summary(
                version = version,
                at = clock.instant(),
                coversFromMessageId = requireNotNull(batch.first().id) { "У сообщения из базы нет номера" },
                coversUptoMessageId = requireNotNull(batch.last().id) { "У сообщения из базы нет номера" },
                foldedMessages = batch.size,
                coveredMessages = (previous?.coveredMessages ?: 0) + batch.size,
                content = folded.content,
                turnNumber = turnNumber,
            )
            val turn = foldTurn(turnNumber, previous, batch, folded, elapsedMs(startedNanos))
            store.saveSummary(summary, turn)
            folds += turn

            log.info(
                "История свёрнута: конспект версии {} покрывает {} сообщений, {} симв. вместо {} симв. " +
                    "переписки — короче в {} раз. Сжатие стоило {} токенов и {}",
                version, summary.coveredMessages, summary.chars, folded.batchChars + folded.previousChars,
                "%.1f".format((folded.batchChars + folded.previousChars).toDouble() / summary.chars.coerceAtLeast(1)),
                turn.totalTokens ?: 0, turn.costUsd?.let { "$%.6f".format(it) } ?: "цену провайдер не сообщил",
            )
            log.debug("\n{}", summaryDump(version, summary, folded))
        }
        return folds
    }

    private fun successfulTurn(
        number: Int,
        context: PromptContext,
        completion: LlmCompletion,
        latencyMs: Long,
        previousTurns: List<Turn>,
    ): Turn {
        val usage: TokenUsage? = completion.usage
        return Turn(
            number = number,
            kind = TurnKind.ANSWER,
            at = clock.instant(),
            mode = policy.mode,
            provider = completion.provider ?: "неизвестен",
            model = completion.model ?: settings.model,
            contextLimit = settings.contextLimit,
            maxTokens = settings.maxTokens,
            promptMessages = context.promptMessages,
            historyTotal = context.historyTotal,
            historyChars = context.historyChars,
            promptBlocks = context.blocks.size,
            charsSent = context.charsSent,
            personaChars = context.personaChars,
            summaryChars = context.summaryChars,
            tailChars = context.tailChars,
            summaryVersion = context.summaryVersion,
            promptTokens = usage?.promptTokens,
            cachedPromptTokens = usage?.cachedPromptTokens,
            completionTokens = usage?.completionTokens,
            totalTokens = usage?.totalTokens,
            costUsd = usage?.costUsd,
            costSource = usage?.costSource,
            latencyMs = latencyMs,
            finishReason = completion.finishReason,
            truncated = truncationSuspected(previousTurns, context, usage?.promptTokens),
        )
    }

    private fun failedTurn(number: Int, context: PromptContext, latencyMs: Long, error: LlmException) = Turn(
        number = number,
        kind = TurnKind.ANSWER,
        at = clock.instant(),
        mode = policy.mode,
        provider = "—",
        model = settings.model,
        contextLimit = settings.contextLimit,
        maxTokens = settings.maxTokens,
        promptMessages = context.promptMessages,
        historyTotal = context.historyTotal,
        historyChars = context.historyChars,
        promptBlocks = context.blocks.size,
        charsSent = context.charsSent,
        personaChars = context.personaChars,
        summaryChars = context.summaryChars,
        tailChars = context.tailChars,
        summaryVersion = context.summaryVersion,
        latencyMs = latencyMs,
        error = error.message,
        errorStatus = error.providerStatus,
    )

    /**
     * Расход сворачивания — в той же таблице, что и ходы, только с [TurnKind.SUMMARY].
     *
     * Состав читается по аналогии с обычным ходом: инструкция конспектёра занимает место
     * персоны, прошлый конспект — место конспекта, стенограмма пачки — место дословного
     * хвоста. Потолок ответа здесь свой, из политики: конспекту отведено меньше, чем ответу.
     */
    private fun foldTurn(
        turnNumber: Int,
        previous: Summary?,
        batch: List<Message>,
        folded: Summarizer.Folded,
        latencyMs: Long,
    ): Turn {
        val usage = folded.completion.usage
        return Turn(
            number = turnNumber,
            kind = TurnKind.SUMMARY,
            at = clock.instant(),
            mode = policy.mode,
            provider = folded.completion.provider ?: "неизвестен",
            model = folded.completion.model ?: settings.summaryModel,
            contextLimit = settings.contextLimit,
            maxTokens = policy.summaryMaxTokens,
            promptMessages = batch.size,
            historyTotal = store.count(),
            historyChars = store.chars(),
            promptBlocks = folded.blocks.size,
            charsSent = folded.charsSent,
            personaChars = folded.instructionChars,
            summaryChars = folded.previousChars,
            tailChars = folded.batchChars,
            summaryVersion = previous?.version,
            promptTokens = usage?.promptTokens,
            cachedPromptTokens = usage?.cachedPromptTokens,
            completionTokens = usage?.completionTokens,
            totalTokens = usage?.totalTokens,
            costUsd = usage?.costUsd,
            costSource = usage?.costSource,
            latencyMs = latencyMs,
            finishReason = folded.completion.finishReason,
        )
    }

    private fun failedFold(
        turnNumber: Int,
        previous: Summary?,
        batch: List<Message>,
        latencyMs: Long,
        error: LlmException,
    ) = Turn(
        number = turnNumber,
        kind = TurnKind.SUMMARY,
        at = clock.instant(),
        mode = policy.mode,
        provider = "—",
        model = settings.summaryModel,
        contextLimit = settings.contextLimit,
        maxTokens = policy.summaryMaxTokens,
        promptMessages = batch.size,
        historyTotal = store.count(),
        historyChars = store.chars(),
        promptBlocks = 2,
        charsSent = batch.sumOf { it.content.length } + (previous?.chars ?: 0),
        personaChars = 0,
        summaryChars = previous?.chars ?: 0,
        tailChars = batch.sumOf { it.content.length },
        summaryVersion = previous?.version,
        latencyMs = latencyMs,
        error = error.message,
        errorStatus = error.providerStatus,
    )

    /**
     * Дошёл ли промпт до модели целиком — или его урезал агрегатор по дороге.
     *
     * Речь не о сжатии этого дня: наше сжатие мы делаем сами и знаем о нём всё. Речь
     * о чужом вмешательстве — OpenRouter умеет молча вырезать середину истории, чтобы
     * запрос влез в лимит, и отвечает `200` с `finish_reason: stop`, как при обычном ходе.
     *
     * Идея проверки: **прирост токенов обязан соответствовать приросту текста.** Сколько
     * символов добавилось к промпту с прошлого хода, мы знаем точно — считаем сами перед
     * отправкой. Во сколько токенов обходится символ в этом диалоге, тоже знаем: это
     * измеренная плотность его же неурезанных ходов. Значит ожидаемый прирост вычисляется,
     * и если фактический заметно ниже — часть промпта до модели не дошла.
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
     * поэтому минимум — самая честная точка отсчёта. Сравниваем только с ходами того же
     * режима: в SUMMARY промпт короче, и мерить его ростом RAW-ходов нельзя.
     *
     * Отдельная оговорка про сжатие — она стоила одного ложного срабатывания на живом
     * прогоне. Вся проверка держится на том, что промпт **прирастает**: к прошлому промпту
     * добавилась пара сообщений, и на неё обязан прирасти счётчик токенов. После сворачивания
     * это неверно: промпт не прирос, а собран заново из других частей — старые сообщения
     * заменены конспектом, хвост стал короче, а плотность нового текста другая. На девятом
     * ходу прогона символов стало больше (в хвост попали длинные технические ответы),
     * а токенов прибавилось мало — и признак сработал на пустом месте.
     *
     * Поэтому сравниваем только ходы одной и той же версии конспекта и только когда хвост
     * не укоротился. Первый ход после сворачивания сравнивать не с чем — для него остаётся
     * грубый предел правдоподобия, как для первого хода диалога.
     */
    private fun truncationSuspected(previous: List<Turn>, context: PromptContext, promptTokens: Int?): Boolean {
        if (promptTokens == null || promptTokens <= 0) return false

        val charsSent = context.charsSent
        val measured = previous.filter {
            it.kind == TurnKind.ANSWER && it.mode == policy.mode && it.error == null && !it.truncated &&
                it.summaryVersion == context.summaryVersion
        }
        val last = measured.lastOrNull()
        val density = measured.mapNotNull { it.charsPerToken }.minOrNull()

        // Первый ход: сравнивать не с чем, плотность этого диалога ещё не измерена.
        // Остаётся грубый предел правдоподобия — он поймает только явное урезание.
        if (last == null || density == null) {
            return charsSent.toDouble() / promptTokens > MAX_PLAUSIBLE_CHARS_PER_TOKEN
        }

        // Хвост укоротился — промпт пересобран, а не продолжен. Модель роста не применима.
        if (context.promptMessages < last.promptMessages) return false

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
     * запроса, и именно здесь видно, что модель получает вместо старых сообщений. Поэтому
     * уровень DEBUG, а не TRACE: включён по умолчанию, гасится одной строкой конфига.
     */
    private fun promptDump(number: Int, input: String, context: PromptContext): String = buildString {
        appendLine("┌── ХОД $number · вход агента (${input.length} симв.)")
        input.lines().forEach { appendLine("│  $it") }
        appendLine(
            "├── КОНТЕКСТ [${policy.mode}] · дословно ${context.promptMessages} из " +
                "${context.historyTotal} сообщений, свёрнуто ${context.foldedMessages}",
        )
        appendLine(
            "│  персона ${context.personaChars} + конспект ${context.summaryChars} + " +
                "хвост ${context.tailChars} + вопрос ${context.questionChars} = ${context.charsSent} симв. " +
                "(без сжатия было бы ${context.rawChars}, короче в ${"%.1f".format(context.ratio)} раз)",
        )
        appendLine("├── ПРОМПТ МОДЕЛИ · ${context.blocks.size} блоков, ${context.charsSent} симв.")
        context.blocks.forEachIndexed { i, block ->
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

    /**
     * Новый конспект целиком.
     *
     * Это единственное место, где видно, что именно останется от свёрнутых сообщений.
     * Читать его глазами обязательно: числа скажут, что промпт стал короче, но не скажут,
     * потерялось ли в нём имя собеседника.
     */
    private fun summaryDump(version: Int, summary: Summary, folded: Summarizer.Folded): String = buildString {
        appendLine(
            "┌── КОНСПЕКТ v$version · ${summary.foldedMessages} новых сообщений " +
                "(#${summary.coversFromMessageId}–#${summary.coversUptoMessageId}), " +
                "покрыто ${summary.coveredMessages} всего",
        )
        appendLine(
            "│  вход: инструкция ${folded.instructionChars} + прошлый конспект ${folded.previousChars} + " +
                "стенограмма ${folded.batchChars} = ${folded.charsSent} симв.",
        )
        appendLine("├── ТЕКСТ (${summary.chars} симв.)")
        summary.content.lines().forEach { appendLine("│  $it") }
        append(
            "└── ${folded.completion.model ?: "модель не указана"}, " +
                "остановка: ${folded.completion.finishReason ?: "не указана"}",
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

package advent.day6.agent

import advent.day6.llm.ApiMessage
import advent.day6.llm.ChatCompletionRequest
import advent.day6.llm.LlmClient
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Агент — отдельная сущность с состоянием, а не обёртка над вызовом API.
 *
 * У него есть имя и характер ([settings]), текущее занятие ([state]), настроение ([mood])
 * и журнал жизни. Каждый вопрос проходит через [ask]: агент собирает промпт из своей
 * персоны и вопроса, идёт к модели, отдаёт ответ по мере генерации. Каждый шаг фиксируется
 * и выдаётся слушателю событием — так снаружи видно, что именно агент делает, а не только
 * что он в итоге сказал.
 *
 * Памяти диалога у агента нет намеренно: каждый вопрос независим. Это ровно то, что просит
 * задание дня, а контекст между ходами — тема следующих дней.
 *
 * Класс не знает ни про Spring, ни про HTTP: единственная зависимость — [LlmClient].
 */
class Agent(
    initialSettings: AgentSettings,
    private val llm: LlmClient,
    private val allowedModels: Set<String>,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    var settings: AgentSettings = initialSettings.also { it.validate(allowedModels) }
        private set

    @Volatile
    var state: AgentState = AgentState.IDLE
        private set

    private val busy = AtomicBoolean(false)
    private val lock = Any()
    private val journal = ArrayDeque<LogEntry>()
    private var turns = 0
    private var failures = 0
    private var promptTokens = 0
    private var completionTokens = 0
    private var totalLatencyMs = 0L
    private var lastTurn: TurnReport? = null
    private var lastActivityAt: Instant? = null
    private val createdAt: Instant = clock.instant()

    init {
        record(LogTone.INFO, "${settings.name} проснулся: модель ${settings.model}")
    }

    val isBusy: Boolean get() = busy.get()

    fun mood(): Mood = synchronized(lock) { moodLocked() }

    fun snapshot(): AgentSnapshot = synchronized(lock) {
        val current = settings
        AgentSnapshot(
            name = current.name,
            avatar = current.avatar,
            state = state,
            mood = moodLocked(),
            settings = current,
            availableModels = allowedModels.sorted(),
            stats = AgentStats(turns, failures, promptTokens, completionTokens, totalLatencyMs),
            lastTurn = lastTurn,
            log = journal.toList(),
            createdAt = createdAt,
            lastActivityAt = lastActivityAt,
        )
    }

    /** Меняет настройки на лету: агент тот же самый, характер другой. */
    fun reconfigure(newSettings: AgentSettings): AgentSnapshot {
        newSettings.validate(allowedModels)
        val changes = newSettings.describeChangesFrom(settings)
        settings = newSettings
        record(
            LogTone.INFO,
            if (changes.isEmpty()) "Настройки сохранены без изменений" else "Перенастроен: ${changes.joinToString("; ")}",
        )
        return snapshot()
    }

    /**
     * Один ход. Блокирует агента на время ответа: параллельный вопрос получит
     * [AgentBusyException]. События уходят [listener]-у синхронно, по мере появления.
     */
    fun ask(input: String, listener: (AgentEvent) -> Unit = {}): TurnReport {
        val text = input.trim()
        require(text.isNotEmpty()) { "Пустой вопрос — отвечать нечего" }
        if (!busy.compareAndSet(false, true)) throw AgentBusyException(settings.name)

        try {
            return Turn(text, listener).run()
        } finally {
            busy.set(false)
        }
    }

    private fun record(tone: LogTone, message: String) = synchronized(lock) {
        journal.addLast(LogEntry(clock.instant(), tone, message))
        while (journal.size > JOURNAL_LIMIT) journal.removeFirst()
        log.info("[{}] {}", settings.name, message)
    }

    private fun moodLocked(): Mood {
        val last = lastTurn
        val activity = lastActivityAt
        return when {
            state == AgentState.THINKING -> Mood.CURIOUS
            state == AgentState.ANSWERING -> Mood.CHATTY
            last == null || activity == null -> Mood.SLEEPY
            Duration.between(activity, clock.instant()) > SLEEPY_AFTER -> Mood.SLEEPY
            last.failed -> Mood.GRUMPY
            last.latencyMs > TIRED_AFTER_MS -> Mood.TIRED
            else -> Mood.CONTENT
        }
    }

    /** Состояние одного хода. Настройки фиксируются на входе: смена персоны посреди ответа его не ломает. */
    private inner class Turn(private val text: String, private val listener: (AgentEvent) -> Unit) {
        private val current = settings
        private val startedAt = clock.instant()
        private val startedNanos = System.nanoTime()
        private val steps = mutableListOf<TurnStep>()
        private val number = synchronized(lock) { turns + failures + 1 }

        private fun elapsedMs() = (System.nanoTime() - startedNanos) / 1_000_000

        private fun step(title: String, detail: String? = null) {
            val step = TurnStep(elapsedMs(), title, detail)
            steps += step
            listener(AgentEvent.Step(step))
        }

        private fun transition(next: AgentState) {
            state = next
            listener(AgentEvent.StateChanged(next, mood()))
        }

        fun run(): TurnReport {
            var promptMessages = 0
            try {
                transition(AgentState.THINKING)
                step("Получил вопрос", "${text.length} символов")

                val messages = buildList {
                    current.persona.takeIf { it.isNotBlank() }?.let { add(ApiMessage("system", it)) }
                    add(ApiMessage("user", text))
                }
                promptMessages = messages.size
                step(
                    "Собрал промпт",
                    if (current.persona.isBlank()) {
                        "только вопрос, ${text.length} символов — персона не задана"
                    } else {
                        "персона и вопрос, ${messages.sumOf { it.content.length }} символов"
                    },
                )

                step("Отправил модели", "${current.model}, temperature ${current.temperature}, до ${current.maxTokens} токенов")

                var firstTokenMs: Long? = null
                var reasoningStarted = false
                val request = ChatCompletionRequest(
                    model = current.model,
                    messages = messages,
                    temperature = current.temperature,
                    maxTokens = current.maxTokens,
                )
                val completion = llm.stream(request) { delta ->
                    delta.reasoning?.takeIf { it.isNotEmpty() }?.let {
                        if (!reasoningStarted) {
                            reasoningStarted = true
                            step("Модель рассуждает", "скрытый ход мысли, в ответ не попадает")
                        }
                        listener(AgentEvent.Reasoning(it))
                    }
                    delta.content?.takeIf { it.isNotEmpty() }?.let {
                        if (firstTokenMs == null) {
                            firstTokenMs = elapsedMs()
                            step("Первый токен", "через $firstTokenMs мс")
                            transition(AgentState.ANSWERING)
                        }
                        listener(AgentEvent.Token(it))
                    }
                }

                val latencyMs = elapsedMs()
                val usage = completion.usage?.toTurnUsage()
                step(
                    "Ответ получен",
                    listOfNotNull(
                        usage?.let {
                            "${it.promptTokens} токенов на входе, ${it.completionTokens} на выходе" +
                                if (it.reasoningTokens > 0) " (из них ${it.reasoningTokens} на рассуждение)" else ""
                        },
                        "$latencyMs мс",
                        completion.finishReason?.takeIf { it != "stop" }?.let { "остановка: $it" },
                    ).joinToString(", "),
                )

                val report = TurnReport(
                    number = number,
                    startedAt = startedAt,
                    input = text,
                    answer = completion.content,
                    reasoning = completion.reasoning,
                    model = completion.model ?: current.model,
                    promptMessages = promptMessages,
                    firstTokenMs = firstTokenMs,
                    latencyMs = latencyMs,
                    usage = usage,
                    finishReason = completion.finishReason,
                    error = null,
                    steps = steps.toList(),
                )
                synchronized(lock) {
                    turns++
                    promptTokens += usage?.promptTokens ?: 0
                    completionTokens += usage?.completionTokens ?: 0
                    totalLatencyMs += latencyMs
                    lastTurn = report
                    lastActivityAt = clock.instant()
                }
                record(LogTone.SUCCESS, "Ход $number: ${usage?.totalTokens?.let { "$it токенов, " } ?: ""}$latencyMs мс")
                transition(AgentState.IDLE)
                listener(AgentEvent.Completed(report))
                return report
            } catch (e: Exception) {
                val message = e.message ?: "Неизвестная ошибка"
                step("Сбой", message)
                val report = TurnReport(
                    number = number,
                    startedAt = startedAt,
                    input = text,
                    answer = null,
                    reasoning = null,
                    model = current.model,
                    promptMessages = promptMessages,
                    firstTokenMs = null,
                    latencyMs = elapsedMs(),
                    usage = null,
                    finishReason = null,
                    error = message,
                    steps = steps.toList(),
                )
                synchronized(lock) {
                    failures++
                    lastTurn = report
                    lastActivityAt = clock.instant()
                }
                record(LogTone.DANGER, "Ход $number не удался: $message")
                transition(AgentState.IDLE)
                listener(AgentEvent.Failed(report))
                return report
            }
        }
    }

    companion object {
        /** Сколько записей журнала держим: интерфейсу нужны последние, а не все. */
        const val JOURNAL_LIMIT = 60
        /** После такой тишины агент засыпает. */
        val SLEEPY_AFTER: Duration = Duration.ofMinutes(2)
        /** Ответ дольше этого считается тяжёлым — агент устаёт. */
        const val TIRED_AFTER_MS = 15_000L
    }
}

package advent.rag.trace

import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.time.TimeSource

/** Один запрос к агенту изнутри: вопрос, пройденные шаги по порядку и чем всё кончилось. */
data class Trace(
    val id: String,
    val at: Instant,
    /** plain или rag. */
    val mode: String,
    val question: String,
    val steps: List<TraceStep>,
    val outcome: Outcome,
    val totalMs: Long,
)

/** Чем кончился запрос. Если шаг упал, следующих шагов в трассировке нет. */
sealed interface Outcome {
    val status: String
}

data class Answered(val answer: String) : Outcome {
    override val status = "answered"
}

data class Failed(val error: String) : Outcome {
    override val status = "failed"
}

/** Строка списка запросов на странице «Внутри агента». */
data class TraceSummary(val id: String, val at: Instant, val mode: String, val question: String, val status: String) {
    companion object {
        fun of(trace: Trace) = TraceSummary(trace.id, trace.at, trace.mode, trace.question, trace.outcome.status)
    }
}

/** Собирает трассировку по ходу одного запроса: агент добавляет шаги по мере их выполнения. */
class TraceRecorder(private val mode: String, private val question: String) {
    val id: String = UUID.randomUUID().toString()
    private val at = Instant.now()
    private val started = TimeSource.Monotonic.markNow()
    private val steps = mutableListOf<TraceStep>()

    operator fun plusAssign(step: TraceStep) {
        steps += step
    }

    fun answered(answer: String) = build(Answered(answer))

    fun failed(error: String) = build(Failed(error))

    private fun build(outcome: Outcome) =
        Trace(id, at, mode, question, steps.toList(), outcome, started.elapsedNow().inWholeMilliseconds)
}

/**
 * Журнал трассировок, новые первыми. Живёт в памяти: после перезапуска пуст, как и лента чата в браузере.
 */
@Component
class TraceJournal {
    private val traces = ConcurrentLinkedDeque<Trace>()

    fun save(trace: Trace) = traces.addFirst(trace)

    fun list(): List<TraceSummary> = traces.map(TraceSummary::of)

    fun get(id: String): Trace = traces.firstOrNull { it.id == id }
        ?: throw NoSuchElementException("Запроса $id нет в журнале: он живёт в памяти и очищается при перезапуске")
}

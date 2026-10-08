package advent.rag.trace

import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.time.TimeSource

data class Trace(
    val id: String,
    val at: Instant,
    val question: String,
    val steps: List<TraceStep>,
    val outcome: Outcome,
    val totalMs: Long,
)

sealed interface Outcome {
    val status: String
}

// status — итог агента: answered или один из видов «не знаю».
data class Answered(override val status: String, val answer: String) : Outcome

data class Failed(val error: String) : Outcome {
    override val status = "failed"
}

data class TraceSummary(val id: String, val at: Instant, val question: String, val status: String) {
    companion object {
        fun of(trace: Trace) = TraceSummary(trace.id, trace.at, trace.question, trace.outcome.status)
    }
}

class TraceRecorder(private val question: String) {
    val id: String = UUID.randomUUID().toString()
    private val at = Instant.now()
    private val started = TimeSource.Monotonic.markNow()
    private val steps = mutableListOf<TraceStep>()

    // Шаги облачной модели приходят из своего потока параллельно с локальными.
    @Synchronized
    operator fun plusAssign(step: TraceStep) {
        steps += step
    }

    fun answered(status: String, answer: String) = build(Answered(status, answer))

    fun failed(error: String) = build(Failed(error))

    @Synchronized
    private fun build(outcome: Outcome) =
        Trace(id, at, question, steps.toList(), outcome, started.elapsedNow().inWholeMilliseconds)
}

@Component
class TraceJournal {
    private val traces = ConcurrentLinkedDeque<Trace>()

    @Synchronized
    fun save(trace: Trace) {
        traces.addFirst(trace)
        while (traces.size > 100) traces.pollLast()
    }

    fun list(): List<TraceSummary> = traces.map(TraceSummary::of)

    fun get(id: String): Trace = traces.firstOrNull { it.id == id }
        ?: throw NoSuchElementException("Запроса $id нет в журнале: он живёт в памяти и очищается при перезапуске")
}

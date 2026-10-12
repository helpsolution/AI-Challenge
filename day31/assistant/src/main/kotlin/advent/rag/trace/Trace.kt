package advent.rag.trace

import advent.rag.agent.HelpAnswer
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

data class Step(val name: String, val status: String, val durationMs: Long, val detail: Any?)
data class Trace(val id: String, val question: String, val startedAt: String,
    val steps: List<Step>, val answer: HelpAnswer?)
class Recorder(val question: String, private val listener: (Step) -> Unit = {}) {
    val id: String = UUID.randomUUID().toString()
    private val start = System.nanoTime()
    private val at = Instant.now().toString()
    private val steps = mutableListOf<Step>()
    val durationMs: Long get() = (System.nanoTime() - start) / 1_000_000
    fun add(name: String, status: String, duration: Long, detail: Any?) {
        val step = Step(name, status, duration, detail)
        steps += step; listener(step)
    }
    fun snapshot(answer: HelpAnswer?) = Trace(id, question, at, steps.toList(), answer)
}
@Component
class TraceJournal {
    private val traces = LinkedHashMap<String, Trace>()
    @Synchronized fun save(trace: Trace) {
        traces[trace.id] = trace
        while (traces.size > 100) traces.remove(traces.keys.first())
    }
    @Synchronized fun get(id: String) = traces[id]
    @Synchronized fun recent() = traces.values.toList().asReversed().map {
        mapOf("id" to it.id, "question" to it.question, "startedAt" to it.startedAt, "status" to it.answer?.status)
    }
}

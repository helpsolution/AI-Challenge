package advent.llmservice.status

import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Один вызов /v1/chat/completions: кто, чем кончился и куда ушло время. */
data class RequestRecord(
    val at: Instant,
    val client: String,
    val status: Int,
    val code: String?,
    val stream: Boolean?,
    val totalMs: Long,
    val queueMs: Long? = null,
    val firstTokenMs: Long? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val tokensPerSecond: Double? = null,
    val finishReason: String? = null,
)

/** Журнал последних запросов и счётчики по статусам с запуска — то, что видно в панели сервиса. */
@Component
class RequestJournal {
    private val recent = ArrayDeque<RequestRecord>()
    private val byStatus = ConcurrentHashMap<Int, AtomicLong>()

    fun record(record: RequestRecord) {
        byStatus.computeIfAbsent(record.status) { AtomicLong() }.incrementAndGet()
        synchronized(recent) {
            recent.addFirst(record)
            if (recent.size > KEEP) recent.removeLast()
        }
    }

    fun recent(): List<RequestRecord> = synchronized(recent) { recent.toList() }

    fun counters(): Map<Int, Long> = byStatus.mapValues { it.value.get() }.toSortedMap()

    private companion object {
        const val KEEP = 50
    }
}

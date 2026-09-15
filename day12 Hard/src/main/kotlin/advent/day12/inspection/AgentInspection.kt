package advent.day12.inspection

import advent.day12.agent.AgentDecision
import advent.day12.agent.PromptBuild
import advent.day12.chat.Session
import advent.day12.config.AgentProperties
import advent.day12.llm.ChatCompletionRequest
import advent.day12.llm.LlmCompletion
import advent.day12.memory.*
import advent.day12.profile.Profile
import advent.day12.profile.ProfileStore
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** A compact state of the organism. Full conversation history stays in the chat. */
data class InspectionState(
    val session: Session,
    val profile: Profile?,
    val working: TaskMemory?,
    val longTerm: List<MemoryItem>,
    val longTermTotal: Int,
    val messageCount: Int,
    val contextMessageIds: List<Long>,
)

data class TraceEvent(val node: String, val label: String, val at: Instant)
data class FieldChange(val field: String, val before: String?, val after: String?)

data class AgentTrace(
    val id: String,
    val sessionId: Long,
    val kind: String,
    val input: String,
    val startedAt: Instant,
    val status: String = "RUNNING",
    val finishedAt: Instant? = null,
    val events: List<TraceEvent> = emptyList(),
    val before: InspectionState,
    val after: InspectionState? = null,
    val prompt: PromptBuild? = null,
    val request: ChatCompletionRequest? = null,
    val completion: LlmCompletion? = null,
    val decision: AgentDecision? = null,
    val changes: List<FieldChange> = emptyList(),
    val error: String? = null,
)

data class TraceSummary(
    val id: String, val kind: String, val input: String, val status: String,
    val startedAt: Instant, val finishedAt: Instant?, val events: List<TraceEvent>,
)

@Repository
class TraceStore(private val jdbc: JdbcClient, private val mapper: ObjectMapper) {
    fun save(trace: AgentTrace): AgentTrace {
        jdbc.sql("""
            INSERT INTO agent_trace (id, session_id, started_at, status, summary, payload)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET status = excluded.status, summary = excluded.summary, payload = excluded.payload
        """.trimIndent()).params(
            trace.id, trace.sessionId, trace.startedAt.toString(), trace.status,
            mapper.writeValueAsString(TraceSummary(trace.id, trace.kind, trace.input.take(120), trace.status,
                trace.startedAt, trace.finishedAt, trace.events)),
            mapper.writeValueAsString(trace),
        ).update()
        return trace
    }

    fun list(sessionId: Long): List<TraceSummary> = jdbc.sql(
        "SELECT summary FROM agent_trace WHERE session_id = ? ORDER BY rowid DESC LIMIT 100",
    ).param(sessionId).query { rs, _ -> mapper.readValue(rs.getString(1), TraceSummary::class.java) }.list()

    fun get(sessionId: Long, id: String): AgentTrace = jdbc.sql(
        "SELECT payload FROM agent_trace WHERE session_id = ? AND id = ?",
    ).params(sessionId, id).query { rs, _ -> mapper.readValue(rs.getString(1), AgentTrace::class.java) }
        .optional().orElseThrow { IllegalArgumentException("Шаг не найден в этой сессии") }

    @EventListener(ApplicationReadyEvent::class)
    fun markInterrupted() {
        jdbc.sql("SELECT payload FROM agent_trace WHERE status = 'RUNNING'")
            .query { rs, _ -> mapper.readValue(rs.getString(1), AgentTrace::class.java) }.list()
            .forEach { save(it.copy(status = "INTERRUPTED", error = "Приложение перезапустилось до завершения шага")) }
    }
}

@Service
class AgentInspection(
    private val shortTerm: ShortTermMemory,
    private val working: WorkingMemory,
    private val longTerm: LongTermMemory,
    private val profiles: ProfileStore,
    val properties: AgentProperties,
    private val clock: Clock,
    val store: TraceStore,
) {
    fun state(sessionId: Long): InspectionState {
        val session = shortTerm.requireSession(sessionId)
        val rules = longTerm.list(Int.MAX_VALUE)
        return InspectionState(session, session.profileId?.let(profiles::profile), working.get(sessionId),
            rules.take(properties.longTermLimit), rules.size, shortTerm.countMessages(sessionId),
            shortTerm.recent(sessionId, session.windowSize).map { it.id })
    }

    fun begin(sessionId: Long, input: String, kind: String = "MESSAGE"): AgentTrace = store.save(
        AgentTrace(UUID.randomUUID().toString(), sessionId, kind, input, clock.instant(), before = state(sessionId),
            events = listOf(TraceEvent("input", "Действие получено", clock.instant()))),
    )

    fun event(trace: AgentTrace, node: String, label: String): AgentTrace = store.save(
        trace.copy(events = trace.events + TraceEvent(node, label, clock.instant())),
    )

    fun finish(trace: AgentTrace, error: String? = null): AgentTrace {
        val after = state(trace.sessionId)
        return store.save(trace.copy(status = if (error == null) "SUCCESS" else "ERROR", finishedAt = clock.instant(),
            after = after, changes = changes(trace.before, after), error = error))
    }

    fun <T> memoryAction(sessionId: Long, label: String, action: () -> T): T {
        var trace = begin(sessionId, label, "MEMORY")
        try {
            trace = event(trace, "router", label)
            val result = action()
            trace = event(trace, "output", "Память обновлена")
            finish(trace)
            return result
        } catch (e: Exception) {
            finish(trace, e.message ?: "Ошибка обновления памяти")
            throw e
        }
    }

    private fun changes(before: InspectionState, after: InspectionState): List<FieldChange> {
        fun fields(s: InspectionState): Map<String, String?> = linkedMapOf(
            "profile" to s.profile?.name,
            "messages" to s.messageCount.toString(),
            "state" to s.working?.state?.name,
            "idea" to s.working?.idea,
            "thesis" to s.working?.thesis,
            "plan" to s.working?.plan?.joinToString("\n"),
            "draft" to s.working?.draft,
            "notes" to s.working?.notes?.joinToString("\n"),
            "styleSuggestion" to s.working?.styleSuggestion?.let { "${it.key}: ${it.value}" },
            "longTerm" to s.longTerm.joinToString("\n") { "${it.key}: ${it.value}" },
        )
        val old = fields(before)
        return fields(after).mapNotNull { (key, value) ->
            if (old[key] == value) null else FieldChange(key, old[key], value)
        }
    }
}

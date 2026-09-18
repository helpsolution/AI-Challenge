package advent.day15.agent

import advent.day15.memory.LongTermMemory
import advent.day15.memory.MemoryItem
import advent.day15.memory.MemoryKind
import advent.day15.memory.NewMemoryItem
import advent.day15.memory.StyleSuggestion
import advent.day15.memory.TaskMemory
import advent.day15.memory.WorkingMemory
import java.time.Clock

data class TaskUpdate(
    val idea: String? = null,
    val thesis: String? = null,
    val plan: List<String>? = null,
    val draft: String? = null,
    val notes: List<String>? = null,
)

data class AgentDecision(
    val reply: String = "",
    val taskUpdate: TaskUpdate? = null,
    val styleSuggestion: StyleSuggestion? = null,
    val invariantCheck: advent.day15.invariant.InvariantCheck? = null,
    val transition: advent.day15.lifecycle.TransitionRequest? = null,
)

class MemoryRouter(
    private val working: WorkingMemory,
    private val longTerm: LongTermMemory,
    private val clock: Clock,
) {
    fun preview(sessionId: Long, decision: AgentDecision): TaskMemory {
        val previous = working.get(sessionId) ?: TaskMemory(sessionId = sessionId, updatedAt = clock.instant())
        val update = decision.taskUpdate
        val suggestion = decision.styleSuggestion
            ?.takeIf { it.key.isNotBlank() && it.value.isNotBlank() }
            ?.let { StyleSuggestion(it.key.trim().take(100), it.value.trim().take(500)) }

        val candidate = previous.copy(
            idea = update?.idea.clean() ?: previous.idea,
            thesis = update?.thesis.clean() ?: previous.thesis,
            plan = update?.plan?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(8) ?: previous.plan,
            draft = update?.draft.clean() ?: previous.draft,
            notes = update?.notes?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(10) ?: previous.notes,
            styleSuggestion = suggestion ?: previous.styleSuggestion,
        )
        return candidate
    }

    /** Save exactly the candidate checked by InvariantGuard, without rebuilding it from mutable memory. */
    fun commit(userText: String, candidate: TaskMemory): MemoryRoutingResult {
        val sessionId = candidate.sessionId
        val saved = working.save(candidate)

        val remembered = if (REMEMBER_RULE.containsMatchIn(userText) && saved.styleSuggestion != null) {
            val item = saveSuggestion(sessionId, saved.styleSuggestion)
            working.save(saved.copy(styleSuggestion = null))
            listOf(item)
        } else {
            emptyList()
        }
        return MemoryRoutingResult(working.get(sessionId), remembered)
    }

    fun acceptStyleSuggestion(sessionId: Long): MemoryItem {
        val task = working.get(sessionId) ?: throw IllegalArgumentException("Пост не найден")
        val suggestion = task.styleSuggestion ?: throw IllegalArgumentException("Нет правила для сохранения")
        val saved = saveSuggestion(sessionId, suggestion)
        working.save(task.copy(styleSuggestion = null))
        return saved
    }

    fun dismissStyleSuggestion(sessionId: Long) {
        val task = working.get(sessionId) ?: return
        working.save(task.copy(styleSuggestion = null))
    }

    private fun saveSuggestion(sessionId: Long, suggestion: StyleSuggestion): MemoryItem =
        longTerm.upsert(
            NewMemoryItem(
                kind = MemoryKind.PREFERENCE,
                key = suggestion.key,
                value = suggestion.value,
                sourceSessionId = sessionId,
            ),
        )

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        val REMEMBER_RULE = Regex("(?iu)(^|\\n)\\s*(запомни|сохрани\\s+.*для\\s+будущих\\s+постов)")
    }
}

data class MemoryRoutingResult(
    val working: TaskMemory?,
    val longTerm: List<MemoryItem>,
)

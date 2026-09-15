package advent.day12.agent

import advent.day12.memory.LongTermMemory
import advent.day12.memory.MemoryItem
import advent.day12.memory.MemoryKind
import advent.day12.memory.NewMemoryItem
import advent.day12.memory.StyleSuggestion
import advent.day12.memory.TaskMemory
import advent.day12.memory.TaskState
import advent.day12.memory.WorkingMemory
import java.time.Clock

data class TaskUpdate(
    val stage: String? = null,
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
)

class MemoryRouter(
    private val working: WorkingMemory,
    private val longTerm: LongTermMemory,
    private val clock: Clock,
) {
    fun observe(sessionId: Long, userText: String, decision: AgentDecision): MemoryRoutingResult {
        val previous = working.get(sessionId) ?: TaskMemory(sessionId = sessionId, updatedAt = clock.instant())
        val update = decision.taskUpdate
        val proposedStage = update?.stage?.let { raw ->
            TaskState.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        }
        val goingBack = proposedStage != null && proposedStage.ordinal < previous.state.ordinal
        val suggestion = decision.styleSuggestion
            ?.takeIf { it.key.isNotBlank() && it.value.isNotBlank() }
            ?.let { StyleSuggestion(it.key.trim().take(100), it.value.trim().take(500)) }

        val candidate = previous.copy(
            idea = update?.idea.clean() ?: previous.idea,
            thesis = update?.thesis.clean() ?: if (goingBack && proposedStage == TaskState.IDEA) null else previous.thesis,
            plan = update?.plan?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(8)
                ?: if (goingBack && proposedStage.ordinal < TaskState.PLAN.ordinal) emptyList() else previous.plan,
            draft = update?.draft.clean() ?: if (goingBack) null else previous.draft,
            notes = update?.notes?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(10) ?: previous.notes,
            styleSuggestion = suggestion ?: previous.styleSuggestion,
        )
        val state = proposedStage?.takeIf { isValidStage(it, candidate) } ?: previous.state
        val saved = working.save(candidate.copy(state = state))

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

    private fun isValidStage(stage: TaskState, task: TaskMemory): Boolean = when (stage) {
        TaskState.IDEA -> true
        TaskState.THESIS -> !task.idea.isNullOrBlank()
        TaskState.PLAN -> !task.thesis.isNullOrBlank()
        TaskState.DRAFT -> task.plan.isNotEmpty() || !task.draft.isNullOrBlank()
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        val REMEMBER_RULE = Regex("(?iu)(^|\\n)\\s*(запомни|сохрани\\s+.*для\\s+будущих\\s+постов)")
    }
}

data class MemoryRoutingResult(
    val working: TaskMemory?,
    val longTerm: List<MemoryItem>,
)

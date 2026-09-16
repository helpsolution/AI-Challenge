package advent.day13.agent

import advent.day13.memory.LongTermMemory
import advent.day13.memory.MemoryItem
import advent.day13.memory.MemoryKind
import advent.day13.memory.NewMemoryItem
import advent.day13.memory.StyleSuggestion
import advent.day13.memory.TaskMemory
import advent.day13.memory.TaskState
import advent.day13.memory.WorkingMemory
import java.time.Clock

data class TaskUpdate(
    val stage: String? = null,
    val currentStep: String? = null,
    val expectedAction: String? = null,
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
                ?: throw IllegalArgumentException("Неизвестный этап задачи: $raw")
        }
        if (proposedStage != null) {
            require(proposedStage in ALLOWED_TRANSITIONS.getValue(previous.state)) {
                "Переход ${previous.state} -> $proposedStage запрещен"
            }
        }
        val goingBack = proposedStage != null && proposedStage.ordinal < previous.state.ordinal
        val suggestion = decision.styleSuggestion
            ?.takeIf { it.key.isNotBlank() && it.value.isNotBlank() }
            ?.let { StyleSuggestion(it.key.trim().take(100), it.value.trim().take(500)) }

        val targetState = proposedStage ?: previous.state
        val candidate = previous.copy(
            currentStep = update?.currentStep.clean()?.take(200)
                ?: if (targetState != previous.state) targetState.defaultCurrentStep else previous.currentStep,
            expectedAction = update?.expectedAction.clean()?.take(300)
                ?: if (targetState != previous.state) targetState.defaultExpectedAction else previous.expectedAction,
            idea = update?.idea.clean() ?: previous.idea,
            thesis = update?.thesis.clean() ?: if (goingBack && proposedStage == TaskState.IDEA) null else previous.thesis,
            plan = update?.plan?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(8)
                ?: if (goingBack && proposedStage.ordinal < TaskState.PLAN.ordinal) emptyList() else previous.plan,
            draft = update?.draft.clean() ?: if (goingBack) null else previous.draft,
            notes = update?.notes?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(10) ?: previous.notes,
            styleSuggestion = suggestion ?: previous.styleSuggestion,
        )
        require(isValidStage(targetState, candidate)) {
            "Для перехода на этап $targetState не хватает данных задачи"
        }
        val saved = working.save(candidate.copy(state = targetState))

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
        val ALLOWED_TRANSITIONS = mapOf(
            TaskState.IDEA to setOf(TaskState.IDEA, TaskState.THESIS),
            TaskState.THESIS to setOf(TaskState.IDEA, TaskState.THESIS, TaskState.PLAN),
            TaskState.PLAN to setOf(TaskState.THESIS, TaskState.PLAN, TaskState.DRAFT),
            TaskState.DRAFT to setOf(TaskState.THESIS, TaskState.PLAN, TaskState.DRAFT),
        )
        val REMEMBER_RULE = Regex("(?iu)(^|\\n)\\s*(запомни|сохрани\\s+.*для\\s+будущих\\s+постов)")
    }
}

data class MemoryRoutingResult(
    val working: TaskMemory?,
    val longTerm: List<MemoryItem>,
)

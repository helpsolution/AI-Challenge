package advent.day14.invariant

import advent.day14.agent.AgentDecision
import advent.day14.memory.TaskMemory

/** One business constraint; check returns true when the candidate satisfies it. */
interface Invariant {
    val id: String
    val description: String
    fun check(candidate: InvariantCandidate): Boolean
}

data class InvariantCandidate(val decision: AgentDecision, val task: TaskMemory) {
    // Check raw proposals too, including fields the memory router would trim or truncate.
    val texts: List<String> get() {
        val update = decision.taskUpdate
        return listOfNotNull(decision.reply, task.idea, task.thesis, task.draft,
            task.styleSuggestion?.key, task.styleSuggestion?.value,
            update?.idea, update?.thesis, update?.draft,
            decision.styleSuggestion?.key, decision.styleSuggestion?.value) +
            task.plan + task.notes + update?.plan.orEmpty() + update?.notes.orEmpty()
    }
}

class MaxTextLength(private val limit: Int, override val description: String) : Invariant {
    override val id = "SHORT_TEXT"

    override fun check(candidate: InvariantCandidate): Boolean =
        listOf(candidate.decision.reply, candidate.task.draft.orEmpty(),
            candidate.decision.taskUpdate?.draft.orEmpty()).all {
            val text = it.trim()
            text.codePointCount(0, text.length) <= limit
        }
}

class NoHashtags(override val description: String) : Invariant {
    override val id = "NO_HASHTAGS"
    private val hashtag = Regex("[#＃][\\p{L}\\p{M}\\p{N}_]")

    override fun check(candidate: InvariantCandidate): Boolean =
        candidate.texts.none { hashtag.containsMatchIn(it) }
}

package advent.day15.invariant

import advent.day15.agent.AgentDecision
import advent.day15.memory.TaskMemory

/** Checks the proposed output before any conversation or memory writes. No extra LLM call. */
class InvariantGuard(val policy: InvariantPolicy = InvariantPolicy.DEFAULT) {
    private val invariants: List<Invariant> = listOf(
        MaxTextLength(policy.maxTextLength, policy.rules.single { it.id == "SHORT_TEXT" }.description),
        NoHashtags(policy.rules.single { it.id == "NO_HASHTAGS" }.description),
    )

    fun check(decision: AgentDecision, candidate: TaskMemory): InvariantVerdict {
        val assessment = decision.invariantCheck
        val ids = policy.rules.map { it.id }.toSet()
        if (assessment == null || assessment.checkedIds.toSet() != ids ||
            assessment.conflictIds.any { it !in ids } || assessment.explanation.isBlank()) {
            return InvariantVerdict(false, reason = "Модель не вернула полную проверку инвариантов. Состояние поста сохранено. Попробуй повторить запрос.")
        }
        val violations = assessment.conflictIds.toMutableSet()
        val proposal = InvariantCandidate(decision, candidate)
        violations += invariants.filterNot { it.check(proposal) }.map { it.id }
        if (violations.isEmpty()) return InvariantVerdict(true, reason = "Ответ и состояние прошли проверку инвариантов.")

        val rules = policy.rules.filter { it.id in violations }
        val reply = "Не могу выполнить запрос в таком виде. Правила канала:\n" +
            rules.joinToString("\n") { "${it.id}: ${it.description}" } +
            "\n\n" + rules.joinToString(" ") { it.alternative } + " Текущий пост и правила стиля не изменены."
        return InvariantVerdict(false, rules.map { it.id }, reply)
    }

}

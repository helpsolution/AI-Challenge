package advent.day15.lifecycle

import advent.day15.memory.TaskMemory
import advent.day15.memory.TaskState

data class TransitionRequest(val target: String = "")

data class TransitionVerdict(
    val allowed: Boolean,
    val from: TaskState,
    val target: TaskState?,
    val allowedTargets: Set<TaskState>,
    val reason: String,
)

data class TransitionEvaluation(
    val candidate: TaskMemory,
    val verdict: TransitionVerdict,
)

/** The model may propose a transition, but only this deterministic policy can apply one. */
class TaskLifecycle {
    private val transitions: Map<TaskState, Set<TaskState>> = mapOf(
        TaskState.IDEA to setOf(TaskState.THESIS),
        TaskState.THESIS to setOf(TaskState.IDEA, TaskState.PLAN),
        TaskState.PLAN to setOf(TaskState.THESIS, TaskState.DRAFT),
        TaskState.DRAFT to setOf(TaskState.PLAN, TaskState.VALIDATION),
        TaskState.VALIDATION to setOf(TaskState.DRAFT, TaskState.DONE),
        TaskState.DONE to emptySet(),
    )

    fun allowedTargets(state: TaskState): Set<TaskState> = transitions.getValue(state)

    fun evaluate(
        current: TaskMemory,
        proposed: TaskMemory,
        request: TransitionRequest?,
        userText: String = "",
        reply: String = "",
    ): TransitionEvaluation {
        val rawTarget = request?.target?.trim()?.takeIf { it.isNotEmpty() }
        val target = rawTarget?.let { raw ->
            TaskState.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        }
        if (rawTarget != null && target == null) {
            return denied(current, "Неизвестный этап «$rawTarget». ${nextHint(current.state)}")
        }

        val destination = target ?: current.state
        if (destination != current.state && destination !in allowedTargets(current.state)) {
            return denied(
                current,
                "Сейчас этап «${label(current.state)}». Переход к этапу «${label(destination)}» запрещен. ${nextHint(current.state)}",
                destination,
            )
        }

        val beforeDraft = destination in setOf(TaskState.IDEA, TaskState.THESIS, TaskState.PLAN)
        val draftWasHiddenInReply = beforeDraft && DRAFT_REQUEST.containsMatchIn(userText) &&
            reply.codePointCount(0, reply.length) >= MIN_DRAFT_REPLY_LENGTH && proposed.draft == current.draft
        if (draftWasHiddenInReply) {
            return denied(
                current,
                "На этапе «${label(current.state)}» нельзя выдавать готовый пост. ${nextHint(current.state)}",
                destination,
            )
        }

        val changedStages = buildSet {
            if (proposed.idea != current.idea) add(TaskState.IDEA)
            if (proposed.thesis != current.thesis) add(TaskState.THESIS)
            if (proposed.plan != current.plan) add(TaskState.PLAN)
            if (proposed.draft != current.draft) add(TaskState.DRAFT)
        }
        val writableStages = setOf(current.state, destination)
        val premature = changedStages.firstOrNull { it !in writableStages }
        if (premature != null) {
            return denied(
                current,
                "Нельзя изменять данные этапа «${label(premature)}», пока задача находится на этапе «${label(current.state)}». ${nextHint(current.state)}",
                destination,
            )
        }
        if (current.state == TaskState.DONE && proposed != current) {
            return denied(current, "Задача уже завершена. Состояние DONE нельзя изменять.", destination)
        }

        val withState = applyState(proposed, current.state, destination)
        val missing = if (destination != current.state) missingRequirement(destination, withState) else null
        if (missing != null) {
            return denied(
                current,
                "Переход к этапу «${label(destination)}» невозможен: $missing.",
                destination,
            )
        }

        return TransitionEvaluation(
            candidate = withState,
            verdict = TransitionVerdict(
                allowed = true,
                from = current.state,
                target = destination,
                allowedTargets = allowedTargets(current.state),
                reason = if (destination == current.state) {
                    "Задача остается на этапе «${label(current.state)}»."
                } else {
                    "Переход «${label(current.state)}» → «${label(destination)}» разрешен."
                },
            ),
        )
    }

    fun prompt(memory: TaskMemory?): String {
        val state = memory?.state ?: TaskState.IDEA
        val allowed = allowedTargets(state).joinToString { it.name }.ifEmpty { "нет" }
        return """
            Жизненный цикл задачи контролируется приложением. Текущий этап: ${state.name}.
            Допустимые переходы из него: $allowed. Вперед и назад можно двигаться только по этой карте:
            IDEA <-> THESIS <-> PLAN <-> DRAFT <-> VALIDATION -> DONE.
            DONE — терминальный этап. Не перепрыгивай этапы.
            Чтобы предложить переход, верни transition={"target":"ЭТАП"}. Если переход не нужен, верни transition=null.
            Условия: для THESIS нужна идея, для PLAN — тезис, для DRAFT — утвержденный план, для VALIDATION — черновик.
            Предлагай PLAN -> DRAFT только после явного согласия пользователя с планом.
            Переход VALIDATION -> DONE означает, что проверка завершилась успешно. Если нужны правки, предложи VALIDATION -> DRAFT.
            Если пользователь просит перепрыгнуть этап, дай короткий отказ и предложи ближайший допустимый шаг. Не выполняй запрещенную работу в reply.
            Полный текст поста допустим только на этапах DRAFT, VALIDATION и DONE и всегда должен быть продублирован в taskUpdate.draft.
        """.trimIndent()
    }

    private fun applyState(proposed: TaskMemory, from: TaskState, target: TaskState): TaskMemory {
        if (target == from) return proposed
        return when (target) {
            TaskState.IDEA -> proposed.copy(state = target, thesis = null, plan = emptyList(), draft = null, notes = emptyList())
            TaskState.THESIS -> proposed.copy(state = target, plan = emptyList(), draft = null, notes = emptyList())
            TaskState.PLAN -> proposed.copy(state = target, draft = null, notes = emptyList())
            TaskState.DRAFT -> proposed.copy(state = target)
            TaskState.VALIDATION -> proposed.copy(state = target)
            TaskState.DONE -> proposed.copy(state = target)
        }
    }

    private fun missingRequirement(target: TaskState, task: TaskMemory): String? = when (target) {
        TaskState.IDEA -> null
        TaskState.THESIS -> if (task.idea.isNullOrBlank()) "сначала нужно зафиксировать идею" else null
        TaskState.PLAN -> if (task.thesis.isNullOrBlank()) "сначала нужно сформулировать тезис" else null
        TaskState.DRAFT -> if (task.plan.isEmpty()) "сначала нужно составить и утвердить план" else null
        TaskState.VALIDATION -> if (task.draft.isNullOrBlank()) "сначала нужен черновик" else null
        TaskState.DONE -> if (task.draft.isNullOrBlank()) "нечего завершать без проверенного черновика" else null
    }

    private fun denied(current: TaskMemory, reason: String, target: TaskState? = null) = TransitionEvaluation(
        candidate = current,
        verdict = TransitionVerdict(false, current.state, target, allowedTargets(current.state), reason),
    )

    private fun nextHint(state: TaskState): String {
        val next = allowedTargets(state).joinToString { "«${label(it)}»" }
        return if (next.isEmpty()) "Допустимых переходов нет." else "Доступные этапы: $next."
    }

    private fun label(state: TaskState): String = when (state) {
        TaskState.IDEA -> "Идея"
        TaskState.THESIS -> "Тезис"
        TaskState.PLAN -> "План"
        TaskState.DRAFT -> "Черновик"
        TaskState.VALIDATION -> "Проверка"
        TaskState.DONE -> "Готово"
    }

    private companion object {
        const val MIN_DRAFT_REPLY_LENGTH = 240
        val DRAFT_REQUEST = Regex(
            "(?isu)(напиши|сделай|создай|подготовь|сгенерируй|составь).{0,80}(пост|текст|черновик|статью|публикацию)",
        )
    }
}

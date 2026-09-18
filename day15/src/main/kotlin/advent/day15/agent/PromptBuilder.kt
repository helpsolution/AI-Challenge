package advent.day15.agent

import advent.day15.chat.Role
import advent.day15.config.AgentProperties
import advent.day15.llm.ApiMessage
import advent.day15.memory.LongTermMemory
import advent.day15.memory.MemoryItem
import advent.day15.memory.MemorySnapshot
import advent.day15.memory.ShortTermMemory
import advent.day15.memory.TaskMemory
import advent.day15.memory.WorkingMemory
import advent.day15.profile.Profile
import advent.day15.profile.ProfileStore
import advent.day15.lifecycle.TaskLifecycle

class PromptBuilder(
    private val shortTerm: ShortTermMemory,
    private val working: WorkingMemory,
    private val longTerm: LongTermMemory,
    private val profiles: ProfileStore,
    private val properties: AgentProperties,
    private val invariants: advent.day15.invariant.InvariantPolicy = advent.day15.invariant.InvariantPolicy.DEFAULT,
    private val lifecycle: TaskLifecycle = TaskLifecycle(),
) {
    fun build(sessionId: Long, question: String): PromptBuild {
        val session = shortTerm.requireSession(sessionId)
        val recent = shortTerm.history(sessionId).takeLast(session.windowSize)
        val task = working.get(sessionId)
        val long = longTerm.list(properties.longTermLimit)
        val profile = session.profileId?.let(profiles::profile)

        val sections = mutableListOf<PromptSection>()
        val blocks = buildList {
            fun block(source: String, role: String, content: String) {
                sections += PromptSection(source, size, content.length)
                add(ApiMessage(role, content))
            }
            block("invariants", "system", invariants.prompt())
            block("lifecycle", "system", lifecycle.prompt(task))
            block("persona", "system", properties.persona.trim())
            profile?.let { block("profile", "system", profileBlock(it)) }
            block("rules", "system", RESPONSE_RULES)
            longTermBlock(long)?.let { block("longTerm", "system", it) }
            block("working", "system", workingBlock(task))
            recent.forEach { message ->
                block("shortTerm", if (message.role == Role.USER) "user" else "assistant", message.content)
            }
            block("input", "user", question)
        }

        return PromptBuild(
            messages = blocks,
            snapshot = MemorySnapshot(recent, task, long, profile),
            charsSent = blocks.sumOf { it.content.length },
            sections = sections,
        )
    }

    private fun profileBlock(profile: Profile): String = """
        Активный профиль: ${profile.name}.
        ${profile.description}

        Применяй профиль как смысловую призму: через него выбирай аргументы, терминологию,
        аналогии и акценты поста. Не вставляй профильные отсылки механически и не выдумывай факты.
    """.trimIndent()

    private fun longTermBlock(items: List<MemoryItem>): String? {
        if (items.isEmpty()) return null
        return buildString {
            appendLine("Подтвержденная долговременная память для будущих постов. Учитывай ее при редактировании.")
            items.groupBy { it.kind }.forEach { (kind, values) ->
                appendLine("${kind.name}:")
                values.forEach { appendLine("- ${it.key}: ${it.value}") }
            }
        }.trim()
    }

    private fun workingBlock(memory: TaskMemory?): String = buildString {
        appendLine("Рабочая память одного текущего поста.")
        appendLine("Этап: ${memory?.state ?: "IDEA"}")
        memory?.idea?.let { appendLine("Идея: $it") }
        memory?.thesis?.let { appendLine("Тезис: $it") }
        if (!memory?.plan.isNullOrEmpty()) {
            appendLine("План:")
            memory.plan.forEachIndexed { index, item -> appendLine("${index + 1}. $item") }
        }
        memory?.draft?.let { appendLine("Текущий черновик:\n$it") }
        if (!memory?.notes.isNullOrEmpty()) {
            appendLine("Открытые замечания:")
            memory.notes.forEach { appendLine("- $it") }
        }
        memory?.styleSuggestion?.let { appendLine("Ожидает подтверждения как правило стиля: ${it.key}: ${it.value}") }
    }.trim()

    private companion object {
        val RESPONSE_RULES = """
            Верни только валидный json-объект с полями reply, taskUpdate, transition, styleSuggestion, invariantCheck, без markdown. Поля taskUpdate, transition и styleSuggestion могут быть null.
            reply - твой естественный ответ пользователю на русском языке, без служебного JSON.
            taskUpdate - только изменившиеся данные поста: idea, thesis, plan (массив строк), draft, notes (массив строк). Пропущенное или null означает "не менять".
            transition - предлагаемая смена этапа в виде {"target":"THESIS"}. Состояние меняет приложение, а не модель.
            Этапы: IDEA - замысел; THESIS - главная мысль; PLAN - структура; DRAFT - текст; VALIDATION - проверка; DONE - завершено.
            Веди пользователя сам, но предлагай только разрешенный соседний переход. Не утверждай тезис или план за пользователя.
            Не обходи жизненный цикл через reply. До этапа DRAFT не выдавай готовый пост или черновик даже по прямой просьбе пользователя.
            Если пользователь просит перескочить этап, коротко объясни отказ и предложи ближайший допустимый шаг.
            На каждом этапе предложи следующий конкретный шаг: один точный вопрос, вариант тезиса, план или полный черновик. Не требуй знания названий этапов.
            Не делай переход механическим: пользователь может сразу принести готовый текст или вернуться к тезису. Не утверждай тезис или план за пользователя.
            В taskUpdate сохраняй только сведения о текущем посте. Если сам написал или изменил черновик, запиши полную актуальную версию в draft.
            styleSuggestion - только устойчивое правило для будущих постов в виде {"key":"...","value":"..."}. Не превращай разовую правку в правило автоматически.
            Если пользователь явно просит запомнить правило, верни его в styleSuggestion. Иначе предложи его сохранить и дождись подтверждения.
            Пример формата: {"reply":"О чем хочешь написать?","taskUpdate":null,"transition":null,"styleSuggestion":null,"invariantCheck":{"checkedIds":["SHORT_TEXT","NO_HASHTAGS"],"conflictIds":[],"explanation":"Уточнение темы соответствует правилам"}}
        """.trimIndent()
    }
}

data class PromptBuild(
    val messages: List<ApiMessage>,
    val snapshot: MemorySnapshot,
    val charsSent: Int,
    val sections: List<PromptSection> = emptyList(),
)

data class PromptSection(val source: String, val messageIndex: Int, val chars: Int)

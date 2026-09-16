package advent.day13.agent

import advent.day13.chat.Role
import advent.day13.config.AgentProperties
import advent.day13.llm.ApiMessage
import advent.day13.memory.LongTermMemory
import advent.day13.memory.MemoryItem
import advent.day13.memory.MemorySnapshot
import advent.day13.memory.ShortTermMemory
import advent.day13.memory.TaskMemory
import advent.day13.memory.TaskState
import advent.day13.memory.WorkingMemory
import advent.day13.profile.Profile
import advent.day13.profile.ProfileStore

class PromptBuilder(
    private val shortTerm: ShortTermMemory,
    private val working: WorkingMemory,
    private val longTerm: LongTermMemory,
    private val profiles: ProfileStore,
    private val properties: AgentProperties,
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
        appendLine("Текущий шаг: ${memory?.currentStep ?: TaskState.IDEA.defaultCurrentStep}")
        appendLine("Ожидаемое действие: ${memory?.expectedAction ?: TaskState.IDEA.defaultExpectedAction}")
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
            Верни только валидный json-объект с полями reply, taskUpdate, styleSuggestion, без markdown. Поля taskUpdate и styleSuggestion могут быть null.
            reply - твой естественный ответ пользователю на русском языке, без служебного JSON.
            taskUpdate - только изменившиеся данные поста: stage (IDEA, THESIS, PLAN, DRAFT), currentStep, expectedAction, idea, thesis, plan (массив строк), draft, notes (массив строк). Пропущенное или null означает "не менять".
            stage - этап, на котором вы сейчас работаете: IDEA - выяснение замысла; THESIS - формулировка главной мысли; PLAN - структура; DRAFT - написание и правки текста.
            currentStep - короткое описание того, что делаем сейчас. expectedAction - одно конкретное действие, которого ждем от пользователя.
            Веди пользователя сам. IDEA -> THESIS, когда понятна тема и можно формулировать мысль. THESIS -> PLAN, когда пользователь принял или уточнил тезис. PLAN -> DRAFT, когда план согласован или пользователь сразу дал готовый текст.
            Работай в рамках текущего этапа и используй только разрешенные соседние переходы. Если пользователь пишет "продолжай", продолжи с текущего шага без повторного сбора уже сохраненных сведений.
            На каждом этапе предложи следующий конкретный шаг: один точный вопрос, вариант тезиса, план или полный черновик. Не требуй знания названий этапов.
            Не делай переход механическим: пользователь может сразу принести готовый текст или вернуться к тезису. Не утверждай тезис или план за пользователя.
            В taskUpdate сохраняй только сведения о текущем посте. Если сам написал или изменил черновик, запиши полную актуальную версию в draft.
            styleSuggestion - только устойчивое правило для будущих постов в виде {"key":"...","value":"..."}. Не превращай разовую правку в правило автоматически.
            Если пользователь явно просит запомнить правило, верни его в styleSuggestion. Иначе предложи его сохранить и дождись подтверждения.
            Пример формата: {"reply":"О чем хочешь написать?","taskUpdate":{"stage":"IDEA","currentStep":"Уточнение идеи","expectedAction":"Пользователь описывает тему поста"},"styleSuggestion":null}
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

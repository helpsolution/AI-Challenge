package advent.day13.memory

import java.time.Instant
import advent.day13.profile.Profile

enum class TaskState(
    val defaultCurrentStep: String,
    val defaultExpectedAction: String,
) {
    IDEA("Уточнение идеи", "Пользователь описывает тему и замысел поста"),
    THESIS("Согласование тезиса", "Пользователь подтверждает или уточняет главную мысль"),
    PLAN("Согласование плана", "Пользователь подтверждает или меняет структуру поста"),
    DRAFT("Работа над черновиком", "Пользователь проверяет текст и сообщает нужные правки"),
}

data class StyleSuggestion(val key: String, val value: String)

data class TaskMemory(
    val sessionId: Long,
    val state: TaskState = TaskState.IDEA,
    val currentStep: String = state.defaultCurrentStep,
    val expectedAction: String = state.defaultExpectedAction,
    val idea: String? = null,
    val thesis: String? = null,
    val plan: List<String> = emptyList(),
    val draft: String? = null,
    val notes: List<String> = emptyList(),
    val styleSuggestion: StyleSuggestion? = null,
    val updatedAt: Instant,
)

enum class MemoryKind { PROFILE, PREFERENCE, DECISION, KNOWLEDGE }

data class MemoryItem(
    val id: Long,
    val kind: MemoryKind,
    val key: String,
    val value: String,
    val confidence: Double,
    val sourceSessionId: Long?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class NewMemoryItem(
    val kind: MemoryKind,
    val key: String,
    val value: String,
    val confidence: Double = 1.0,
    val sourceSessionId: Long? = null,
)

data class MemorySnapshot(
    val shortTerm: List<advent.day13.chat.Message>,
    val working: TaskMemory?,
    val longTerm: List<MemoryItem>,
    val profile: Profile?,
)

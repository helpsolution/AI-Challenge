package advent.day15.memory

import java.time.Instant
import advent.day15.profile.Profile

enum class TaskState { IDEA, THESIS, PLAN, DRAFT, VALIDATION, DONE }

data class StyleSuggestion(val key: String, val value: String)

data class TaskMemory(
    val sessionId: Long,
    val state: TaskState = TaskState.IDEA,
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
    val shortTerm: List<advent.day15.chat.Message>,
    val working: TaskMemory?,
    val longTerm: List<MemoryItem>,
    val profile: Profile?,
)

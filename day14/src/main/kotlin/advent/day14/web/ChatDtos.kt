package advent.day14.web

import advent.day14.chat.Message
import advent.day14.chat.Session
import advent.day14.memory.MemoryItem
import advent.day14.memory.MemoryKind
import advent.day14.memory.TaskMemory
import advent.day14.profile.Profile

data class AgentView(
    val name: String,
    val model: String,
    val maxTokens: Int,
    val temperature: Double,
    val defaultWindowSize: Int,
    val longTermLimit: Int,
)

data class SessionSummary(
    val session: Session,
    val messages: Int,
    val working: TaskMemory?,
    val profile: Profile?,
)

data class SessionView(
    val session: Session,
    val messages: List<Message>,
    val working: TaskMemory?,
    val longTerm: List<MemoryItem>,
    val profile: Profile?,
)

data class CreateSessionRequest(
    val title: String? = null,
    val windowSize: Int? = null,
)

data class AskRequest(val text: String)

data class AskResponse(
    val question: Message,
    val answer: Message,
    val working: TaskMemory?,
    val longTermSaved: List<MemoryItem>,
)

data class UpsertMemoryRequest(
    val kind: MemoryKind,
    val key: String,
    val value: String,
    val confidence: Double = 1.0,
)

data class ProfileRequest(
    val name: String,
    val description: String,
)

data class ErrorResponse(
    val error: String,
    val providerStatus: Int? = null,
    val providerBody: String? = null,
)

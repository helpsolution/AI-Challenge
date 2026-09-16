package advent.day13.chat

import java.time.Instant

enum class Role { USER, ASSISTANT }

data class Message(
    val id: Long,
    val sessionId: Long,
    val role: Role,
    val content: String,
    val at: Instant,
)

data class Session(
    val id: Long,
    val title: String,
    val windowSize: Int,
    val profileId: Long? = null,
    val createdAt: Instant,
)

data class Exchange(
    val question: Message,
    val answer: Message,
)

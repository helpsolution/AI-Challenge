package advent.posts.memory

import advent.posts.chat.Message
import advent.posts.chat.Role
import advent.posts.chat.Session

interface ShortTermMemory {
    fun sessions(): List<Session>
    fun session(id: Long): Session?
    fun requireSession(id: Long): Session =
        session(id) ?: throw IllegalArgumentException("Сессия $id не найдена")

    fun createSession(title: String, windowSize: Int): Session
    fun setProfile(sessionId: Long, profileId: Long): Session
    fun renameSession(sessionId: Long, title: String)
    fun deleteSession(id: Long)
    fun history(sessionId: Long): List<Message>
    fun recent(sessionId: Long, limit: Int): List<Message>
    fun countMessages(sessionId: Long): Int
    fun saveMessage(sessionId: Long, role: Role, content: String): Message
}

interface WorkingMemory {
    fun get(sessionId: Long): TaskMemory?
    fun save(memory: TaskMemory): TaskMemory
    fun clear(sessionId: Long)
}

interface LongTermMemory {
    fun list(limit: Int): List<MemoryItem>
    fun upsert(item: NewMemoryItem): MemoryItem
    fun deleteItem(id: Long)
}

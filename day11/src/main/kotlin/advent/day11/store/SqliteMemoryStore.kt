package advent.day11.store

import advent.day11.chat.Message
import advent.day11.chat.Role
import advent.day11.chat.Session
import advent.day11.memory.LongTermMemory
import advent.day11.memory.MemoryItem
import advent.day11.memory.MemoryKind
import advent.day11.memory.NewMemoryItem
import advent.day11.memory.ShortTermMemory
import advent.day11.memory.StyleSuggestion
import advent.day11.memory.TaskMemory
import advent.day11.memory.TaskState
import advent.day11.memory.WorkingMemory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant

@Repository
class SqliteMemoryStore(
    private val jdbc: JdbcClient,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : ShortTermMemory, WorkingMemory, LongTermMemory {

    override fun sessions(): List<Session> =
        jdbc.sql("SELECT * FROM session ORDER BY id DESC")
            .query { rs, _ -> rs.toSession() }
            .list()

    override fun session(id: Long): Session? =
        jdbc.sql("SELECT * FROM session WHERE id = ?")
            .param(id)
            .query { rs, _ -> rs.toSession() }
            .optional()
            .orElse(null)

    override fun createSession(title: String, windowSize: Int): Session {
        val at = clock.instant()
        val keys = GeneratedKeyHolder()
        jdbc.sql("INSERT INTO session (title, window_size, created_at) VALUES (?, ?, ?)")
            .params(title, windowSize, at.toString())
            .update(keys)
        val id = keys.key?.toLong() ?: error("SQLite не вернул идентификатор сессии")
        return Session(id, title, windowSize, at)
    }

    @Transactional
    override fun deleteSession(id: Long) {
        jdbc.sql("UPDATE memory_item SET source_session_id = NULL WHERE source_session_id = ?").param(id).update()
        jdbc.sql("DELETE FROM post_task WHERE session_id = ?").param(id).update()
        jdbc.sql("DELETE FROM message WHERE session_id = ?").param(id).update()
        jdbc.sql("DELETE FROM session WHERE id = ?").param(id).update()
    }

    override fun history(sessionId: Long): List<Message> =
        jdbc.sql("SELECT * FROM message WHERE session_id = ? ORDER BY id")
            .param(sessionId)
            .query { rs, _ -> rs.toMessage() }
            .list()

    override fun recent(sessionId: Long, limit: Int): List<Message> =
        jdbc.sql(
            """
            SELECT * FROM (
                SELECT * FROM message WHERE session_id = ? ORDER BY id DESC LIMIT ?
            ) ORDER BY id
            """.trimIndent(),
        )
            .params(sessionId, limit)
            .query { rs, _ -> rs.toMessage() }
            .list()

    override fun countMessages(sessionId: Long): Int =
        jdbc.sql("SELECT count(*) FROM message WHERE session_id = ?")
            .param(sessionId)
            .query(Int::class.java)
            .single()

    override fun saveMessage(sessionId: Long, role: Role, content: String): Message {
        val at = clock.instant()
        val keys = GeneratedKeyHolder()
        jdbc.sql("INSERT INTO message (session_id, role, content, created_at) VALUES (?, ?, ?, ?)")
            .params(sessionId, role.name, content, at.toString())
            .update(keys)
        val id = keys.key?.toLong() ?: error("SQLite не вернул идентификатор сообщения")
        return Message(id, sessionId, role, content, at)
    }

    override fun get(sessionId: Long): TaskMemory? =
        jdbc.sql("SELECT * FROM post_task WHERE session_id = ?")
            .param(sessionId)
            .query { rs, _ -> rs.toTaskMemory() }
            .optional()
            .orElse(null)

    override fun save(memory: TaskMemory): TaskMemory {
        val at = clock.instant()
        val stored = memory.copy(updatedAt = at)
        jdbc.sql(
            """
            INSERT INTO post_task (session_id, state, idea, thesis, plan, draft, notes, style_suggestion, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (session_id)
            DO UPDATE SET state = excluded.state,
                          idea = excluded.idea,
                          thesis = excluded.thesis,
                          plan = excluded.plan,
                          draft = excluded.draft,
                          notes = excluded.notes,
                          style_suggestion = excluded.style_suggestion,
                          updated_at = excluded.updated_at
            """.trimIndent(),
        )
            .params(
                listOf(
                    stored.sessionId,
                    stored.state.name,
                    stored.idea,
                    stored.thesis,
                    objectMapper.writeValueAsString(stored.plan),
                    stored.draft,
                    objectMapper.writeValueAsString(stored.notes),
                    stored.styleSuggestion?.let { objectMapper.writeValueAsString(it) },
                    stored.updatedAt.toString(),
                ),
            )
            .update()
        return stored
    }

    override fun clear(sessionId: Long) {
        jdbc.sql("DELETE FROM post_task WHERE session_id = ?").param(sessionId).update()
    }

    override fun list(limit: Int): List<MemoryItem> =
        jdbc.sql("SELECT * FROM memory_item ORDER BY kind, key LIMIT ?")
            .param(limit)
            .query { rs, _ -> rs.toMemoryItem() }
            .list()

    override fun upsert(item: NewMemoryItem): MemoryItem {
        val now = clock.instant()
        jdbc.sql(
            """
            INSERT INTO memory_item (kind, key, value, confidence, source_session_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (kind, key)
            DO UPDATE SET value = excluded.value,
                          confidence = excluded.confidence,
                          source_session_id = excluded.source_session_id,
                          updated_at = excluded.updated_at
            """.trimIndent(),
        )
            .params(
                listOf(
                    item.kind.name,
                    item.key,
                    item.value,
                    item.confidence,
                    item.sourceSessionId,
                    now.toString(),
                    now.toString(),
                ),
            )
            .update()

        return jdbc.sql("SELECT * FROM memory_item WHERE kind = ? AND key = ?")
            .params(item.kind.name, item.key)
            .query { rs, _ -> rs.toMemoryItem() }
            .single()
    }

    override fun deleteItem(id: Long) {
        jdbc.sql("DELETE FROM memory_item WHERE id = ?").param(id).update()
    }

    private fun ResultSet.toSession() = Session(
        id = getLong("id"),
        title = getString("title"),
        windowSize = getInt("window_size"),
        createdAt = Instant.parse(getString("created_at")),
    )

    private fun ResultSet.toMessage() = Message(
        id = getLong("id"),
        sessionId = getLong("session_id"),
        role = Role.valueOf(getString("role")),
        content = getString("content"),
        at = Instant.parse(getString("created_at")),
    )

    private fun ResultSet.toTaskMemory() = TaskMemory(
        sessionId = getLong("session_id"),
        state = TaskState.valueOf(getString("state")),
        idea = getString("idea"),
        thesis = getString("thesis"),
        plan = jsonList(getString("plan")),
        draft = getString("draft"),
        notes = jsonList(getString("notes")),
        styleSuggestion = getString("style_suggestion")?.let { objectMapper.readValue(it, StyleSuggestion::class.java) },
        updatedAt = Instant.parse(getString("updated_at")),
    )

    private fun ResultSet.toMemoryItem() = MemoryItem(
        id = getLong("id"),
        kind = MemoryKind.valueOf(getString("kind")),
        key = getString("key"),
        value = getString("value"),
        confidence = getDouble("confidence"),
        sourceSessionId = longOrNull("source_session_id"),
        createdAt = Instant.parse(getString("created_at")),
        updatedAt = Instant.parse(getString("updated_at")),
    )

    private fun jsonList(value: String): List<String> =
        objectMapper.readValue(value, object : TypeReference<List<String>>() {})

    private fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }
}

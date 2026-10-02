package advent.rag.chat

import advent.rag.agent.Turn
import advent.rag.memory.TaskState
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

@ConfigurationProperties("chat")
data class ChatProperties(val storeFile: Path, val historyTurns: Int) {
    init { require(historyTurns >= 0) { "chat.history-turns не может быть отрицательным" } }
}

data class ConversationSummary(val id: String, val title: String, val goal: String?, val turns: Int, val updatedAt: Instant)

data class Conversation(
    val id: String, val title: String, val createdAt: Instant, val updatedAt: Instant,
    val memory: TaskState, val turns: List<Turn>,
)

/** Диалоги в отдельном файле: пересборка индекса знаний их не затрагивает. */
@Component
class ChatStore(properties: ChatProperties, private val json: JsonMapper) {
    private val path = properties.storeFile

    init {
        path.toAbsolutePath().parent?.let(Files::createDirectories)
        connect().use { db ->
            db.createStatement().use {
                it.executeUpdate("CREATE TABLE IF NOT EXISTS conversations (id TEXT PRIMARY KEY, title TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, memory TEXT NOT NULL)")
                // Первичный ключ не даст двум одновременным ответам записаться под одним номером.
                it.executeUpdate("CREATE TABLE IF NOT EXISTS turns (conversation_id TEXT NOT NULL, number INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY (conversation_id, number))")
            }
        }
    }

    private fun connect() = DriverManager.getConnection("jdbc:sqlite:$path")

    fun create(): Conversation {
        val now = Instant.now()
        val conversation = Conversation(UUID.randomUUID().toString(), NEW_TITLE, now, now, TaskState(), emptyList())
        connect().use { db ->
            db.prepareStatement("INSERT INTO conversations VALUES (?, ?, ?, ?, ?)").use {
                it.setString(1, conversation.id); it.setString(2, conversation.title); it.setString(3, now.toString())
                it.setString(4, now.toString()); it.setString(5, json.writeValueAsString(conversation.memory))
                it.executeUpdate()
            }
        }
        return conversation
    }

    fun list(): List<ConversationSummary> = connect().use { db ->
        db.createStatement().use { statement ->
            statement.executeQuery("SELECT c.*, (SELECT COUNT(*) FROM turns t WHERE t.conversation_id = c.id) AS turns FROM conversations c ORDER BY updated_at DESC").use { rows ->
                buildList {
                    while (rows.next()) add(ConversationSummary(rows.getString("id"), rows.getString("title"),
                        memory(rows.getString("memory")).goal, rows.getInt("turns"), Instant.parse(rows.getString("updated_at"))))
                }
            }
        }
    }

    fun get(id: String): Conversation = connect().use { db ->
        db.autoCommit = false
        val conversation = db.prepareStatement("SELECT * FROM conversations WHERE id = ?").use { select ->
            select.setString(1, id)
            select.executeQuery().use { rows ->
                if (!rows.next()) throw NoSuchElementException("Диалога $id нет")
                Conversation(id, rows.getString("title"), Instant.parse(rows.getString("created_at")),
                    Instant.parse(rows.getString("updated_at")), memory(rows.getString("memory")), turns(db, id))
            }
        }
        db.commit()
        conversation
    }

    @Synchronized
    fun append(id: String, turn: Turn) = connect().use { db ->
        db.autoCommit = false
        try {
            db.prepareStatement("INSERT INTO turns VALUES (?, ?, ?)").use {
                it.setString(1, id); it.setInt(2, turn.number); it.setString(3, json.writeValueAsString(turn))
                it.executeUpdate()
            }
            db.prepareStatement("UPDATE conversations SET memory = ?, updated_at = ?, title = CASE WHEN ? = 1 THEN ? ELSE title END WHERE id = ?").use {
                it.setString(1, json.writeValueAsString(turn.memory)); it.setString(2, turn.at.toString())
                it.setInt(3, turn.number); it.setString(4, title(turn.question)); it.setString(5, id)
                check(it.executeUpdate() == 1) { "Диалог $id удалён во время ответа" }
            }
            db.commit()
        } catch (e: Exception) { db.rollback(); throw e }
    }

    fun delete(id: String) = connect().use { db ->
        db.autoCommit = false
        db.prepareStatement("DELETE FROM turns WHERE conversation_id = ?").use { it.setString(1, id); it.executeUpdate() }
        val deleted = db.prepareStatement("DELETE FROM conversations WHERE id = ?").use { it.setString(1, id); it.executeUpdate() }
        if (deleted == 0) { db.rollback(); throw NoSuchElementException("Диалога $id нет") }
        db.commit()
    }

    private fun turns(db: Connection, id: String): List<Turn> =
        db.prepareStatement("SELECT payload FROM turns WHERE conversation_id = ? ORDER BY number").use { select ->
            select.setString(1, id)
            select.executeQuery().use { rows -> buildList { while (rows.next()) add(json.readValue(rows.getString(1), Turn::class.java)) } }
        }

    private fun memory(raw: String) = json.readValue(raw, TaskState::class.java)

    private fun title(question: String) = question.lineSequence().first().let { if (it.length > 80) it.take(79) + "…" else it }

    private companion object { const val NEW_TITLE = "Новый диалог" }
}

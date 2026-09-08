package advent.day7.store

import advent.day7.chat.Message
import advent.day7.chat.Role
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Реализация на SQLite. Единственное место в приложении, которое знает SQL.
 *
 * Порядок диалога задаёт `id`: `AUTOINCREMENT` монотонен, поэтому сортировка по нему —
 * и есть хронология. На `at` сортировку не завязываем: оба сообщения одного хода
 * получают близкие метки времени и могут совпасть.
 */
@Repository
class SqliteMessageStore(private val jdbc: JdbcClient) : MessageStore {

    override fun history(): List<Message> =
        jdbc.sql("SELECT role, content, created_at FROM message ORDER BY id")
            .query { rs, _ ->
                Message(
                    role = Role.valueOf(rs.getString("role")),
                    content = rs.getString("content"),
                    at = Instant.parse(rs.getString("created_at")),
                )
            }
            .list()

    @Transactional
    override fun append(question: Message, answer: Message) {
        insert(question)
        insert(answer)
    }

    override fun clear() {
        jdbc.sql("DELETE FROM message").update()
    }

    override fun count(): Int =
        jdbc.sql("SELECT count(*) FROM message").query(Int::class.java).single()

    private fun insert(message: Message) {
        jdbc.sql("INSERT INTO message (role, content, created_at) VALUES (?, ?, ?)")
            .params(message.role.name, message.content, message.at.toString())
            .update()
    }
}

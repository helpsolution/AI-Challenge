package advent.users.storage

import advent.users.web.User
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.temporal.ChronoUnit

@Repository
class UserRepository(private val jdbc: JdbcClient) {
    fun insert(name: String, email: String): User {
        val createdAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
        val keys = GeneratedKeyHolder()
        jdbc.sql("INSERT INTO users (name, name_ci, email, created_at) VALUES (?, ?, ?, ?)")
            .params(name, name.lowercase(), email, createdAt)
            .update(keys)
        return User(id = keys.key!!.toLong(), name = name, email = email, createdAt = createdAt)
    }

    fun search(query: String, limit: Int): List<User> =
        jdbc.sql(SEARCH_SQL)
            .param("pattern", likePattern(query))
            .param("limit", limit)
            .query(ROW_MAPPER)
            .list()

    /**
     * Подстрока ищется по name_ci — имени в нижнем регистре, записанному при вставке.
     * Свой lower() у SQLite умеет только ASCII: «Иван» и «иван» он бы не сопоставил.
     * Email уже хранится нормализованным, поэтому отдельная колонка ему не нужна.
     */
    private fun likePattern(query: String): String {
        val escaped = query.lowercase()
            .replace("!", "!!")
            .replace("%", "!%")
            .replace("_", "!_")
        return "%$escaped%"
    }

    private companion object {
        val SEARCH_SQL = """
            SELECT id, name, email, created_at
            FROM users
            WHERE name_ci LIKE :pattern ESCAPE '!' OR email LIKE :pattern ESCAPE '!'
            ORDER BY id
            LIMIT :limit
        """.trimIndent()

        val ROW_MAPPER = RowMapper { rs, _ ->
            User(
                id = rs.getLong("id"),
                name = rs.getString("name"),
                email = rs.getString("email"),
                createdAt = rs.getString("created_at"),
            )
        }
    }
}

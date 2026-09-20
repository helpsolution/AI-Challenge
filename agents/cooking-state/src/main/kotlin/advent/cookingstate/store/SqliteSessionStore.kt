package advent.cookingstate.store

import advent.cookingstate.agent.CookingContext
import advent.cookingstate.agent.CookingSession
import advent.cookingstate.agent.CookingState
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

@Repository
class SqliteSessionStore(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) : SessionStore {
    init {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS cooking_sessions (
                id TEXT PRIMARY KEY,
                state TEXT NOT NULL,
                context_json TEXT NOT NULL,
                reply TEXT NOT NULL,
                version INTEGER NOT NULL,
                updated_at TEXT NOT NULL
            )
        """.trimIndent())
    }

    override fun create(): CookingSession {
        val session = CookingSession(
            id = UUID.randomUUID().toString(),
            state = CookingState.GATHERING,
            context = CookingContext(),
            reply = "Расскажи, какие продукты есть и сколько времени на готовку.",
            version = 0,
            updatedAt = Instant.now(),
        )
        jdbc.update(
            "INSERT INTO cooking_sessions(id, state, context_json, reply, version, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
            session.id, session.state.name, mapper.writeValueAsString(session.context),
            session.reply, session.version, session.updatedAt.toString(),
        )
        return session
    }

    override fun find(id: String): CookingSession? = jdbc.query(
        "SELECT id, state, context_json, reply, version, updated_at FROM cooking_sessions WHERE id = ?",
        { rs: ResultSet, _: Int -> CookingSession(
            id = rs.getString("id"),
            state = CookingState.valueOf(rs.getString("state")),
            context = mapper.readValue(rs.getString("context_json"), CookingContext::class.java),
            reply = rs.getString("reply"),
            version = rs.getInt("version"),
            updatedAt = Instant.parse(rs.getString("updated_at")),
        ) }, id,
    ).firstOrNull()

    override fun save(previousVersion: Int, session: CookingSession): Boolean = jdbc.update(
        """UPDATE cooking_sessions SET state = ?, context_json = ?, reply = ?, version = ?, updated_at = ?
           WHERE id = ? AND version = ?""",
        session.state.name, mapper.writeValueAsString(session.context), session.reply,
        session.version, session.updatedAt.toString(), session.id, previousVersion,
    ) == 1
}

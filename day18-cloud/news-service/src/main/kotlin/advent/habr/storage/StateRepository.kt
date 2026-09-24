package advent.habr.storage

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant

/** Строка collector_state: включён ли сбор и чем кончился последний. */
data class CollectorState(
    val enabled: Boolean,
    val lastRunAt: Instant?,
    val lastFound: Int?,
    val lastAdded: Int?,
    val lastError: String?,
)

@Repository
class StateRepository(private val jdbc: JdbcClient) {
    fun get(): CollectorState = jdbc.sql("SELECT * FROM collector_state WHERE id = 1").query(ROW_MAPPER).single()

    fun setEnabled(enabled: Boolean) {
        jdbc.sql("UPDATE collector_state SET enabled = :enabled WHERE id = 1")
            .param("enabled", if (enabled) 1 else 0)
            .update()
    }

    fun recordRun(runAt: Instant, found: Int, added: Int, error: String?) {
        jdbc.sql(
            """
            UPDATE collector_state
            SET last_run_at = :runAt, last_found = :found, last_added = :added, last_error = :error
            WHERE id = 1
            """.trimIndent(),
        )
            .param("runAt", runAt.toString())
            .param("found", found)
            .param("added", added)
            .param("error", error)
            .update()
    }

    private companion object {
        val ROW_MAPPER = RowMapper { rs, _ ->
            CollectorState(
                enabled = rs.getInt("enabled") == 1,
                lastRunAt = rs.getString("last_run_at")?.let(Instant::parse),
                lastFound = rs.getObject("last_found")?.let { (it as Number).toInt() },
                lastAdded = rs.getObject("last_added")?.let { (it as Number).toInt() },
                lastError = rs.getString("last_error"),
            )
        }
    }
}

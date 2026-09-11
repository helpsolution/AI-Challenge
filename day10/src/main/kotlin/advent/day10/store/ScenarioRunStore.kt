package advent.day10.store

import advent.day10.scenario.ScenarioRun
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper

/**
 * Прогоны сценария. Отдельно от [ChatStore] потому, что это другая сущность: там
 * переписка, здесь измерение переписки. Общего у них — только `session_id`.
 */
interface ScenarioRunStore {

    /** Все прогоны, новые сверху. Из них строится таблица сравнения стратегий. */
    fun all(): List<ScenarioRun>

    /** Прогон по сессии, в которой он шёл. Пока его нет — прогон ещё идёт. */
    fun bySession(sessionId: Long): ScenarioRun?

    fun save(run: ScenarioRun): ScenarioRun
}

@Repository
class SqliteScenarioRunStore(
    private val jdbc: JdbcClient,
    private val objectMapper: ObjectMapper,
) : ScenarioRunStore {

    override fun all(): List<ScenarioRun> =
        jdbc.sql("SELECT id, report FROM scenario_run ORDER BY id DESC")
            .query { rs, _ -> read(rs.getLong("id"), rs.getString("report")) }.list()

    override fun bySession(sessionId: Long): ScenarioRun? =
        jdbc.sql("SELECT id, report FROM scenario_run WHERE session_id = ? ORDER BY id DESC LIMIT 1")
            .param(sessionId)
            .query { rs, _ -> read(rs.getLong("id"), rs.getString("report")) }
            .optional().orElse(null)

    override fun save(run: ScenarioRun): ScenarioRun {
        val keys = GeneratedKeyHolder()
        jdbc.sql(
            """
            INSERT INTO scenario_run (scenario_id, strategy, session_id, created_at, report)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
        )
            .params(
                run.scenarioId, run.strategy.name, run.sessionId,
                run.finishedAt.toString(), objectMapper.writeValueAsString(run),
            )
            .update(keys)
        val id = keys.key?.toLong() ?: error("SQLite не вернул идентификатор прогона")
        return run.copy(id = id)
    }

    /**
     * Идентификатор берём из колонки, а не из JSON: в момент записи его ещё не было,
     * и в сохранённом отчёте лежит ноль. Колонка — единственный источник правды.
     */
    private fun read(id: Long, report: String): ScenarioRun =
        objectMapper.readValue(report, ScenarioRun::class.java).copy(id = id)
}

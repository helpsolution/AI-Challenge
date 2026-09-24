package advent.pipeline.storage

import advent.pipeline.web.Source
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

data class StoredReport(
    val id: Long,
    val createdAt: String,
    val query: String?,
    val summary: String,
    val sources: List<Source>,
)

@Repository
class ReportRepository(private val jdbc: JdbcClient, private val json: JsonMapper) {

    fun insert(createdAt: Instant, query: String?, summary: String, sources: List<Source>): Long =
        jdbc.sql(
            """
            INSERT INTO reports (created_at, query, summary, sources)
            VALUES (:createdAt, :query, :summary, :sources)
            RETURNING id
            """.trimIndent(),
        )
            .param("createdAt", createdAt.toString())
            .param("query", query)
            .param("summary", summary)
            .param("sources", json.writeValueAsString(sources))
            .query(Long::class.java)
            .single()

    fun latest(): StoredReport? =
        jdbc.sql("SELECT * FROM reports ORDER BY id DESC LIMIT 1").query(rowMapper).optional().orElse(null)

    private val rowMapper = RowMapper { rs, _ ->
        StoredReport(
            id = rs.getLong("id"),
            createdAt = rs.getString("created_at"),
            query = rs.getString("query"),
            summary = rs.getString("summary"),
            sources = json.readValue(rs.getString("sources"), SOURCES),
        )
    }

    private companion object {
        val SOURCES = object : TypeReference<List<Source>>() {}
    }
}

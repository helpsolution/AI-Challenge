package advent.news.storage

import advent.news.web.Subscription
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Repository
class SubscriptionRepository(private val jdbc: JdbcClient) {
    /**
     * Подписка создаётся, а если такая уже есть — у неё меняется интервал.
     * Уникальность держит ограничение UNIQUE (kind, target), а не проверка «сначала поищу»:
     * INSERT OR IGNORE сам скажет, вставил он строку или нет.
     */
    @Transactional
    fun upsert(kind: String, target: String, title: String, everyMinutes: Int, now: Instant): Pair<Subscription, Boolean> {
        val inserted = jdbc.sql(
            """
            INSERT OR IGNORE INTO subscriptions (kind, target, title, every_minutes, created_at, next_run_at)
            VALUES (:kind, :target, :title, :every, :now, :next)
            """.trimIndent(),
        )
            .param("kind", kind)
            .param("target", target)
            .param("title", title)
            .param("every", everyMinutes)
            .param("now", now.toString())
            // Первый сбор делает сама подписка; тик в это время не должен схватить ту же ленту второй раз.
            .param("next", now.plusMinutes(everyMinutes).toString())
            .update() == 1

        val existing = findBy(kind, target)
        if (!inserted && existing.everyMinutes != everyMinutes) {
            // Новый интервал отсчитывается от последнего сбора: иначе смена интервала сдвинула бы расписание.
            val base = existing.lastRunAt?.let(Instant::parse) ?: now
            jdbc.sql("UPDATE subscriptions SET every_minutes = :every, next_run_at = :next WHERE id = :id")
                .param("every", everyMinutes)
                .param("next", base.plusMinutes(everyMinutes).toString())
                .param("id", existing.id)
                .update()
            return (findById(existing.id) ?: existing) to false
        }
        return existing to inserted
    }

    fun findAll(): List<Subscription> =
        jdbc.sql("$SELECT ORDER BY s.id").query(ROW_MAPPER).list()

    fun findById(id: Long): Subscription? =
        jdbc.sql("$SELECT WHERE s.id = :id").param("id", id).query(ROW_MAPPER).optional().orElse(null)

    /** Подписки, которым пора обновиться: именно так планировщик узнаёт, что делать на очередном тике. */
    fun findDue(now: Instant): List<Subscription> =
        jdbc.sql("$SELECT WHERE s.next_run_at <= :now ORDER BY s.next_run_at")
            .param("now", now.toString())
            .query(ROW_MAPPER)
            .list()

    fun recordRun(id: Long, runAt: Instant, nextRunAt: Instant, added: Int, error: String?) {
        jdbc.sql(
            """
            UPDATE subscriptions
            SET last_run_at = :runAt, next_run_at = :next, last_added = :added, last_error = :error
            WHERE id = :id
            """.trimIndent(),
        )
            .param("runAt", runAt.toString())
            .param("next", nextRunAt.toString())
            .param("added", added)
            .param("error", error)
            .param("id", id)
            .update()
    }

    /** Вместе с подпиской уходят и её новости: без подписки их некому обновлять и не к чему отнести. */
    @Transactional
    fun delete(id: Long): Boolean {
        jdbc.sql("DELETE FROM articles WHERE subscription_id = :id").param("id", id).update()
        return jdbc.sql("DELETE FROM subscriptions WHERE id = :id").param("id", id).update() == 1
    }

    private fun findBy(kind: String, target: String): Subscription =
        jdbc.sql("$SELECT WHERE s.kind = :kind AND s.target = :target")
            .param("kind", kind)
            .param("target", target)
            .query(ROW_MAPPER)
            .single()

    private fun Instant.plusMinutes(minutes: Int): Instant = plusSeconds(minutes * 60L)

    private companion object {
        val SELECT = """
            SELECT s.id, s.kind, s.target, s.title, s.every_minutes, s.created_at, s.next_run_at,
                   s.last_run_at, s.last_added, s.last_error,
                   (SELECT COUNT(*) FROM articles a WHERE a.subscription_id = s.id) AS articles
            FROM subscriptions s
        """.trimIndent()

        val ROW_MAPPER = RowMapper { rs, _ ->
            Subscription(
                id = rs.getLong("id"),
                kind = rs.getString("kind"),
                target = rs.getString("target"),
                title = rs.getString("title"),
                everyMinutes = rs.getInt("every_minutes"),
                createdAt = rs.getString("created_at"),
                nextRunAt = rs.getString("next_run_at"),
                lastRunAt = rs.getString("last_run_at"),
                lastAdded = rs.getObject("last_added")?.let { (it as Number).toInt() },
                lastError = rs.getString("last_error"),
                articles = rs.getInt("articles"),
            )
        }
    }
}

package advent.news.storage

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** Новость, готовая к записи: издание и время уже определены. */
data class NewArticle(
    val source: String,
    val title: String,
    val link: String,
    val publishedAt: Instant,
)

/** Новость, прочитанная для сводки, вместе с подпиской, которая её принесла. */
data class StoredArticle(
    val id: Long,
    val source: String,
    val title: String,
    val titleCi: String,
    val link: String,
    val publishedAt: String,
    val subscriptionId: Long,
    val subscriptionKind: String,
    val subscriptionTitle: String,
)

/**
 * Какие новости взять в сводку. Период задаётся одним из двух способов:
 *  - publishedSince — «что вышло за последний час», по времени публикации;
 *  - afterId — «что пришло после прошлой сводки», по курсору. Так сводки по таймеру идут
 *    встык: новость, опубликованную в 16:29 и собранную в 16:31, не теряет ни одна из них.
 */
data class ArticleFilter(
    val upToId: Long,
    val publishedSince: Instant? = null,
    val afterId: Long? = null,
    val query: String? = null,
    val subscriptionId: Long? = null,
)

@Repository
class ArticleRepository(private val jdbc: JdbcClient) {
    /**
     * Лента при каждом сборе отдаёт почти те же новости, что и в прошлый раз.
     * Повторы молча отбрасывает UNIQUE (subscription_id, link) через INSERT OR IGNORE,
     * а число реально вставленных строк и есть «сколько новых».
     */
    @Transactional
    fun insertAll(subscriptionId: Long, articles: List<NewArticle>, fetchedAt: Instant): Int =
        articles.sumOf { article ->
            jdbc.sql(
                """
                INSERT OR IGNORE INTO articles (subscription_id, source, title, title_ci, link, published_at, fetched_at)
                VALUES (:subscription, :source, :title, :titleCi, :link, :published, :fetched)
                """.trimIndent(),
            )
                .param("subscription", subscriptionId)
                .param("source", article.source)
                .param("title", article.title)
                .param("titleCi", article.title.lowercase())
                .param("link", article.link)
                .param("published", article.publishedAt.toString())
                .param("fetched", fetchedAt.toString())
                .update()
        }

    /**
     * Курсор — id последней записанной новости. id растёт в порядке записи, а пишет в базу
     * одно соединение, поэтому «всё, что id > курсора» — это ровно то, что появилось после.
     */
    fun lastId(): Long = jdbc.sql("SELECT COALESCE(MAX(id), 0) FROM articles").query(Long::class.java).single()

    /**
     * Новости для сводки, новые сверху. Верхняя граница upToId обязательна: курсор читается
     * отдельным запросом, и новость, записанная между ним и этим запросом, иначе попала бы
     * в сводку, но не в курсор — и пришла бы второй раз.
     */
    fun find(filter: ArticleFilter): List<StoredArticle> {
        val conditions = buildList {
            add("a.id <= :upToId")
            if (filter.publishedSince != null) add("a.published_at >= :since")
            if (filter.afterId != null) add("a.id > :afterId")
            if (filter.query != null) add("a.title_ci LIKE :pattern ESCAPE '!'")
            if (filter.subscriptionId != null) add("a.subscription_id = :subscription")
        }
        var statement = jdbc.sql(
            """
            SELECT a.id, a.source, a.title, a.title_ci, a.link, a.published_at,
                   s.id AS subscription_id, s.kind, s.title AS subscription_title
            FROM articles a
            JOIN subscriptions s ON s.id = a.subscription_id
            WHERE ${conditions.joinToString(" AND ")}
            ORDER BY a.published_at DESC, a.id DESC
            """.trimIndent(),
        ).param("upToId", filter.upToId)
        filter.publishedSince?.let { statement = statement.param("since", it.toString()) }
        filter.afterId?.let { statement = statement.param("afterId", it) }
        filter.query?.let { statement = statement.param("pattern", likePattern(it)) }
        filter.subscriptionId?.let { statement = statement.param("subscription", it) }
        return statement.query(ROW_MAPPER).list()
    }

    fun deleteOlderThan(cutoff: Instant): Int =
        jdbc.sql("DELETE FROM articles WHERE published_at < :cutoff")
            .param("cutoff", cutoff.toString())
            .update()

    /** Поиск идёт по title_ci: свой lower() у SQLite не понимает кириллицу. */
    private fun likePattern(query: String): String {
        val escaped = query.lowercase()
            .replace("!", "!!")
            .replace("%", "!%")
            .replace("_", "!_")
        return "%$escaped%"
    }

    private companion object {
        val ROW_MAPPER = RowMapper { rs, _ ->
            StoredArticle(
                id = rs.getLong("id"),
                source = rs.getString("source"),
                title = rs.getString("title"),
                titleCi = rs.getString("title_ci"),
                link = rs.getString("link"),
                publishedAt = rs.getString("published_at"),
                subscriptionId = rs.getLong("subscription_id"),
                subscriptionKind = rs.getString("kind"),
                subscriptionTitle = rs.getString("subscription_title"),
            )
        }
    }
}

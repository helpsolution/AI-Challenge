package advent.habr.storage

import advent.habr.feed.FeedItem
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** Статья, прочитанная для сводки. */
data class StoredArticle(
    val id: Long,
    val title: String,
    val link: String,
    val author: String?,
    val categories: List<String>,
    val excerpt: String?,
    val publishedAt: String,
)

@Repository
class ArticleRepository(private val jdbc: JdbcClient) {
    /**
     * Лента при каждом сборе отдаёт почти те же статьи, что и в прошлый раз.
     * Повторы молча отбрасывает UNIQUE (link) через INSERT OR IGNORE,
     * а число реально вставленных строк и есть «сколько новых».
     */
    @Transactional
    fun insertAll(items: List<FeedItem>, fetchedAt: Instant): Int =
        items.sumOf { item ->
            jdbc.sql(
                """
                INSERT OR IGNORE INTO articles (link, title, author, categories, excerpt, published_at, fetched_at)
                VALUES (:link, :title, :author, :categories, :excerpt, :published, :fetched)
                """.trimIndent(),
            )
                .param("link", item.link)
                .param("title", item.title)
                .param("author", item.author)
                .param("categories", item.categories.joinToString(CATEGORY_SEPARATOR))
                .param("excerpt", item.excerpt)
                // Дата из будущего бывает у лент с кривыми часами — такая статья висела бы в каждой сводке.
                .param("published", minOf(item.publishedAt ?: fetchedAt, fetchedAt).toString())
                .param("fetched", fetchedAt.toString())
                .update()
        }

    /**
     * Курсор — id последней записанной статьи. id растёт в порядке записи, а пишет в базу
     * одно соединение, поэтому «всё, что id > курсора» — это ровно то, что появилось после.
     */
    fun lastId(): Long = jdbc.sql("SELECT COALESCE(MAX(id), 0) FROM articles").query(Long::class.java).single()

    fun count(): Int = jdbc.sql("SELECT COUNT(*) FROM articles").query(Int::class.java).single()

    /**
     * Статьи для сводки, новые сверху. Период задаётся либо publishedSince — «что вышло за последние
     * N минут», либо afterId — «что собрано после прошлой сводки». Верхняя граница upToId обязательна:
     * курсор читается отдельным запросом, и статья, записанная между ним и этим запросом, иначе
     * попала бы в сводку, но не в курсор — и пришла бы второй раз.
     */
    fun find(upToId: Long, publishedSince: Instant?, afterId: Long?): List<StoredArticle> {
        val conditions = buildList {
            add("id <= :upToId")
            if (publishedSince != null) add("published_at >= :since")
            if (afterId != null) add("id > :afterId")
        }
        var statement = jdbc.sql(
            """
            SELECT id, title, link, author, categories, excerpt, published_at
            FROM articles
            WHERE ${conditions.joinToString(" AND ")}
            ORDER BY published_at DESC, id DESC
            """.trimIndent(),
        ).param("upToId", upToId)
        publishedSince?.let { statement = statement.param("since", it.toString()) }
        afterId?.let { statement = statement.param("afterId", it) }
        return statement.query(ROW_MAPPER).list()
    }

    fun deleteOlderThan(cutoff: Instant): Int =
        jdbc.sql("DELETE FROM articles WHERE published_at < :cutoff")
            .param("cutoff", cutoff.toString())
            .update()

    private companion object {
        const val CATEGORY_SEPARATOR = "\n"

        val ROW_MAPPER = RowMapper { rs, _ ->
            StoredArticle(
                id = rs.getLong("id"),
                title = rs.getString("title"),
                link = rs.getString("link"),
                author = rs.getString("author"),
                categories = rs.getString("categories").split(CATEGORY_SEPARATOR).filter { it.isNotEmpty() },
                excerpt = rs.getString("excerpt"),
                publishedAt = rs.getString("published_at"),
            )
        }
    }
}

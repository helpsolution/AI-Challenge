package advent.habr.service

import advent.habr.storage.ArticleRepository
import advent.habr.web.Article
import advent.habr.web.CategoryCount
import advent.habr.web.Digest
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Агрегация — работа кода, а не модели: сколько статей и какие теги встречались чаще всего.
 * Модели остаётся то, что код сделать не может, — понять, о чём статьи, и разложить их по рубрикам.
 */
@Service
class DigestService(private val articles: ArticleRepository) {
    /**
     * Период задаётся либо minutes — «что вышло за последние N минут», либо afterId — «что собрано
     * после прошлой сводки». Второй способ — для сводок по таймеру: каждая следующая начинается
     * ровно с того места, где кончилась предыдущая, и статья, попавшая в ленту с опозданием, не теряется.
     */
    fun digest(minutes: Int?, afterId: Long?, limit: Int): Digest {
        require(minutes == null || afterId == null) { "Задайте либо minutes, либо afterId, но не оба сразу" }
        require(minutes == null || minutes in MINUTES) { "Период должен быть от ${MINUTES.first} до ${MINUTES.last} минут" }
        require(afterId == null || afterId >= 0) { "Параметр afterId не может быть отрицательным" }
        require(limit in LIMIT) { "Параметр limit должен быть от ${LIMIT.first} до ${LIMIT.last}" }

        val cursor = articles.lastId()
        val to = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val window = if (afterId == null) minutes ?: DEFAULT_MINUTES else null
        val from = window?.let { to.minus(it.toLong(), ChronoUnit.MINUTES) }
        val rows = articles.find(upToId = cursor, publishedSince = from, afterId = afterId)

        val byCategory = rows.flatMap { it.categories }
            .groupingBy { it.lowercase() }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(TOP_CATEGORIES)
            .map { (category, count) -> CategoryCount(category, count) }

        return Digest(
            from = from?.toString(),
            to = to.toString(),
            minutes = window,
            afterId = afterId,
            cursor = cursor,
            total = rows.size,
            byCategory = byCategory,
            shown = minOf(limit, rows.size),
            articles = rows.take(limit).map {
                Article(
                    id = it.id,
                    publishedAt = it.publishedAt,
                    title = it.title,
                    link = it.link,
                    author = it.author,
                    categories = it.categories,
                    excerpt = it.excerpt,
                )
            },
        )
    }

    private companion object {
        /** Не больше срока хранения по умолчанию: старше 30 дней статей в базе уже нет. */
        val MINUTES = 1..30 * 24 * 60
        const val DEFAULT_MINUTES = 24 * 60
        val LIMIT = 1..300
        const val TOP_CATEGORIES = 15
    }
}

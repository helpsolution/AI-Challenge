package advent.news.service

import advent.news.storage.ArticleFilter
import advent.news.storage.ArticleRepository
import advent.news.storage.SubscriptionRepository
import advent.news.web.Digest
import advent.news.web.Headline
import advent.news.web.SourceCount
import advent.news.web.SubscriptionCount
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Агрегация — работа кода, а не модели: посчитать, сколько новостей и откуда, и убрать повторы.
 * Модели остаётся то, что код сделать не может, — понять смысл и сгруппировать по сюжетам.
 */
@Service
class DigestService(
    private val articles: ArticleRepository,
    private val subscriptions: SubscriptionRepository,
) {
    /**
     * Период задаётся либо minutes — «что вышло за последние N минут», либо afterId — «что собрано
     * после прошлой сводки». Второй способ — для сводок по таймеру: каждая следующая начинается
     * ровно с того места, где кончилась предыдущая, и отстающая лента ничего не теряет.
     */
    fun digest(minutes: Int?, afterId: Long?, query: String?, subscriptionId: Long?, limit: Int): Digest {
        require(minutes == null || afterId == null) { "Задайте либо minutes, либо afterId, но не оба сразу" }
        require(minutes == null || minutes in MINUTES) { "Период должен быть от ${MINUTES.first} до ${MINUTES.last} минут" }
        require(afterId == null || afterId >= 0) { "Параметр afterId не может быть отрицательным" }
        require(limit in LIMIT) { "Параметр limit должен быть от ${LIMIT.first} до ${LIMIT.last}" }
        val filter = query?.trim()?.takeIf { it.isNotEmpty() }
        require(filter == null || filter.length <= MAX_QUERY_LENGTH) { "Фильтр длиннее $MAX_QUERY_LENGTH символов" }
        if (subscriptionId != null && subscriptions.findById(subscriptionId) == null) {
            throw NoSuchElementException("Подписки #$subscriptionId нет")
        }

        val cursor = articles.lastId()
        val to = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val window = if (afterId == null) minutes ?: DEFAULT_MINUTES else null
        val from = window?.let { to.minus(it.toLong(), ChronoUnit.MINUTES) }
        val word = filter?.let(::wordStart)
        val rows = articles.find(
            ArticleFilter(
                upToId = cursor,
                publishedSince = from,
                afterId = afterId,
                query = filter,
                subscriptionId = subscriptionId,
            ),
        ).filter { word == null || word.containsMatchIn(it.titleCi) }

        // Одна новость может прийти дважды: из ленты ТАСС и из темы, которую нашёл Google News.
        // Считаем её один раз, но помним все издания и темы, где она встретилась.
        val headlines = rows.groupBy { it.titleCi }.values.map { same ->
            val newest = same.first()
            Headline(
                publishedAt = newest.publishedAt,
                sources = same.map { it.source }.distinct(),
                title = newest.title,
                link = newest.link,
                topics = same.filter { it.subscriptionKind == KIND_TOPIC }.map { it.subscriptionTitle }.distinct(),
            )
        }

        val bySource = headlines.flatMap { it.sources }
            .groupingBy { it }
            .eachCount()
            .map { (source, count) -> SourceCount(source, count) }
            .sortedByDescending { it.count }

        val bySubscription = rows.groupBy { it.subscriptionId }
            .map { (id, items) ->
                SubscriptionCount(
                    subscriptionId = id,
                    kind = items.first().subscriptionKind,
                    title = items.first().subscriptionTitle,
                    count = items.size,
                )
            }
            .sortedByDescending { it.count }

        return Digest(
            from = from?.toString(),
            to = to.toString(),
            minutes = window,
            afterId = afterId,
            cursor = cursor,
            query = filter,
            subscriptionId = subscriptionId,
            total = headlines.size,
            bySource = bySource,
            bySubscription = bySubscription,
            shown = minOf(limit, headlines.size),
            headlines = headlines.take(limit),
        )
    }

    /**
     * LIKE в базе ищет подстроку, и «ИИ» находит «России». Поэтому совпадение засчитывается
     * только с начала слова: «ии» найдёт «ИИ-агентов», а «трамп» — «Трампа».
     */
    private fun wordStart(filter: String): Regex =
        Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(filter.lowercase()))

    private companion object {
        /** Не больше срока хранения по умолчанию: старше семи дней новостей в базе уже нет. */
        val MINUTES = 1..7 * 24 * 60
        const val DEFAULT_MINUTES = 60
        val LIMIT = 1..300
        const val MAX_QUERY_LENGTH = 100
    }
}

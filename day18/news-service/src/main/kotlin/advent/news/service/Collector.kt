package advent.news.service

import advent.news.config.NewsProperties
import advent.news.feed.FeedClient
import advent.news.feed.FeedException
import advent.news.storage.ArticleRepository
import advent.news.storage.NewArticle
import advent.news.storage.SubscriptionRepository
import advent.news.web.RunResult
import advent.news.web.Subscription
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Планировщик — то, ради чего сделан день 18.
 *
 * Расписание хранится в базе: у каждой подписки есть next_run_at. Раз в news.tick планировщик
 * просыпается, берёт подписки, которым пора, и забирает их ленты. Поэтому ничего не теряется
 * при перезапуске: сервис поднимется, прочитает next_run_at и продолжит с того же места.
 */
@Component
class Collector(
    private val properties: NewsProperties,
    private val feeds: FeedClient,
    private val subscriptions: SubscriptionRepository,
    private val articles: ArticleRepository,
) {
    private val log = LoggerFactory.getLogger(Collector::class.java)

    @Scheduled(fixedDelayString = "\${news.tick}", initialDelayString = "PT5S")
    fun tick() {
        val now = now()
        for (subscription in subscriptions.findDue(now)) {
            // Одна сломанная подписка не должна останавливать остальные.
            runCatching { collect(subscription) }
                .onFailure { log.error("Сбор подписки #{} упал", subscription.id, it) }
        }

        val removed = articles.deleteOlderThan(now.minus(properties.retention))
        if (removed > 0) log.info("Удалено новостей старше {}: {}", properties.retention, removed)
    }

    /** Один сбор: скачать ленту, записать новое, запомнить итог и время следующего сбора. */
    fun collect(subscription: Subscription): RunResult {
        val startedAt = now()
        val result = try {
            val items = feeds.fetch(urlFor(subscription))
            val added = articles.insertAll(
                subscriptionId = subscription.id,
                articles = items.map { item ->
                    NewArticle(
                        source = item.source ?: defaultSource(subscription),
                        title = item.title,
                        link = item.link,
                        // Дата из будущего бывает у лент с кривыми часами — такая новость висела бы в каждой сводке.
                        publishedAt = minOf(item.publishedAt ?: startedAt, startedAt),
                    )
                },
                fetchedAt = startedAt,
            )
            log.info("#{} {}: в ленте {}, новых {}", subscription.id, label(subscription), items.size, added)
            RunResult(found = items.size, added = added, error = null)
        } catch (e: FeedException) {
            log.warn("#{} {}: {}", subscription.id, label(subscription), e.message)
            RunResult(found = 0, added = 0, error = e.message)
        }

        subscriptions.recordRun(
            id = subscription.id,
            runAt = startedAt,
            nextRunAt = startedAt.plus(subscription.everyMinutes.toLong(), ChronoUnit.MINUTES),
            added = result.added,
            error = result.error,
        )
        return result
    }

    /**
     * Адрес собирается здесь и только здесь: ленты — из каталога, темы — по шаблону Google News.
     * Модель влияет только на текст запроса, и тот уходит в URL закодированным.
     */
    private fun urlFor(subscription: Subscription): URI = when (subscription.kind) {
        KIND_FEED -> properties.sources[subscription.target]?.url?.let(::URI)
            ?: throw FeedException("ленты «${subscription.target}» больше нет в каталоге")

        else -> {
            val google = properties.googleNews
            // when:1d — только за последние сутки: без него поиск отдаёт и статьи месячной давности.
            val query = URLEncoder.encode("${subscription.target} when:1d", Charsets.UTF_8)
            URI("${google.url}?q=$query&hl=${google.language}&gl=${google.country}&ceid=${google.country}:${google.language}")
        }
    }

    private fun defaultSource(subscription: Subscription): String =
        if (subscription.kind == KIND_FEED) subscription.title else "Google News"

    private fun label(subscription: Subscription): String =
        if (subscription.kind == KIND_FEED) subscription.title else "тема «${subscription.title}»"

    private fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
}

const val KIND_FEED = "feed"
const val KIND_TOPIC = "topic"

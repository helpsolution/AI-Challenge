package advent.habr.service

import advent.habr.config.NewsProperties
import advent.habr.feed.FeedClient
import advent.habr.feed.FeedException
import advent.habr.storage.ArticleRepository
import advent.habr.storage.CollectorState
import advent.habr.storage.StateRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Итог одного сбора. */
data class RunResult(val found: Int, val added: Int, val error: String?)

/**
 * Планировщик — то, ради чего сделан день 18.
 *
 * Раз в минуту он просыпается и сверяет время последнего сбора из базы с news.every.
 * Расписание не держится в памяти, поэтому перезапуск ничего не сбивает: сервис поднимется,
 * прочитает last_run_at и заберёт ленту тогда, когда подошёл срок, а не сразу после старта.
 */
@Component
class Collector(
    private val properties: NewsProperties,
    private val feed: FeedClient,
    private val articles: ArticleRepository,
    private val state: StateRepository,
) {
    private val log = LoggerFactory.getLogger(Collector::class.java)

    /** Сбор по таймеру и сбор при включении через API не должны скачивать ленту одновременно. */
    private val lock = ReentrantLock()

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT5S")
    fun tick() {
        val now = now()
        val current = state.get()
        if (current.enabled && isDue(current, now)) {
            // Сбой одного сбора не должен останавливать планировщик: следующий придёт в срок.
            runCatching { collect() }.onFailure { log.error("Сбор упал", it) }
        }

        val removed = articles.deleteOlderThan(now.minus(properties.retention))
        if (removed > 0) log.info("Удалено статей старше {}: {}", properties.retention, removed)
    }

    /** Один сбор: скачать ленту, записать новое и запомнить итог. */
    fun collect(): RunResult = lock.withLock {
        val startedAt = now()
        val result = try {
            val items = feed.fetch()
            val added = articles.insertAll(items, startedAt)
            log.info("Хабр: в ленте {}, новых {}", items.size, added)
            RunResult(found = items.size, added = added, error = null)
        } catch (e: FeedException) {
            log.warn("Хабр: {}", e.message)
            RunResult(found = 0, added = 0, error = e.message)
        }
        state.recordRun(startedAt, result.found, result.added, result.error)
        result
    }

    fun nextRunAt(current: CollectorState): Instant? = when {
        !current.enabled -> null
        else -> current.lastRunAt?.plus(properties.every) ?: now()
    }

    private fun isDue(current: CollectorState, now: Instant): Boolean {
        val next = nextRunAt(current) ?: return false
        return !now.isBefore(next)
    }

    private fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
}

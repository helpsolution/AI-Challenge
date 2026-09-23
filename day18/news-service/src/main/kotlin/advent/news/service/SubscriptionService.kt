package advent.news.service

import advent.news.config.NewsProperties
import advent.news.storage.SubscriptionRepository
import advent.news.web.Source
import advent.news.web.SubscribeResult
import advent.news.web.Subscription
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Подписки: проверки, нормализация и первый сбор сразу при подписке. */
@Service
class SubscriptionService(
    private val properties: NewsProperties,
    private val repository: SubscriptionRepository,
    private val collector: Collector,
) {
    fun sources(): List<Source> =
        properties.sources.map { (key, source) -> Source(key = key, title = source.title, url = source.url) }

    /** Ленту можно назвать и ключом, и названием: модели проще сказать «ТАСС», чем помнить «tass». */
    fun subscribeFeed(source: String, everyMinutes: Int?): SubscribeResult {
        val wanted = source.trim().lowercase()
        require(wanted.isNotEmpty()) { "Поле source не может быть пустым" }
        val (key, entry) = properties.sources.entries
            .firstOrNull { (key, entry) -> key == wanted || entry.title.lowercase() == wanted }
            ?.let { it.key to it.value }
            ?: throw IllegalArgumentException(
                "Неизвестная лента «$source». Доступны: " +
                    properties.sources.entries.joinToString(", ") { "${it.key} (${it.value.title})" },
            )
        return subscribe(KIND_FEED, key, entry.title, everyMinutes)
    }

    fun subscribeTopic(query: String, everyMinutes: Int?): SubscribeResult {
        val topic = query.trim().replace(WHITESPACE, " ")
        require(topic.length in TOPIC_LENGTH) {
            "Тема должна быть от ${TOPIC_LENGTH.first} до ${TOPIC_LENGTH.last} символов"
        }
        return subscribe(KIND_TOPIC, topic.lowercase(), topic, everyMinutes)
    }

    fun list(): List<Subscription> = repository.findAll()

    fun unsubscribe(id: Long) {
        if (!repository.delete(id)) throw NoSuchElementException("Подписки #$id нет")
    }

    private fun subscribe(kind: String, target: String, title: String, everyMinutes: Int?): SubscribeResult {
        val every = everyMinutes ?: DEFAULT_EVERY_MINUTES
        require(every in EVERY_MINUTES) {
            "Интервал должен быть от ${EVERY_MINUTES.first} до ${EVERY_MINUTES.last} минут"
        }

        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val (subscription, created) = repository.upsert(kind, target, title, every, now)
        if (!created) return SubscribeResult(subscription, created = false, firstRun = null)

        // Первый сбор — сразу, не дожидаясь тика: сводку можно просить сразу после подписки.
        val firstRun = collector.collect(subscription)
        return SubscribeResult(repository.findById(subscription.id) ?: subscription, created = true, firstRun = firstRun)
    }

    private companion object {
        const val DEFAULT_EVERY_MINUTES = 10
        val EVERY_MINUTES = 1..1440
        val TOPIC_LENGTH = 2..100
        val WHITESPACE = Regex("\\s+")
    }
}

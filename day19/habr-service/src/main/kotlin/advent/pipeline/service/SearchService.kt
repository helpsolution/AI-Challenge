package advent.pipeline.service

import advent.pipeline.feed.FeedClient
import advent.pipeline.web.Article
import advent.pipeline.web.SearchResult
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class SearchService(private val feed: FeedClient) {
    fun search(query: String?, limit: Int): SearchResult {
        val topic = query?.trim()?.takeIf { it.isNotEmpty() }
        require(topic == null || topic.length <= MAX_QUERY_LENGTH) { "Запрос длиннее $MAX_QUERY_LENGTH символов" }
        require(limit in LIMIT) { "Параметр limit должен быть от ${LIMIT.first} до ${LIMIT.last}" }

        val items = if (topic == null) feed.latest(limit) else feed.search(topic, limit)
        return SearchResult(
            query = topic,
            source = if (topic == null) "Свежая лента Хабра" else "Поиск по Хабру",
            fetchedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
            count = items.size,
            items = items.map {
                Article(
                    id = it.id,
                    title = it.title,
                    link = it.link,
                    author = it.author,
                    publishedAt = it.publishedAt?.toString(),
                    tags = it.tags,
                    text = it.text,
                )
            },
        )
    }

    companion object {
        const val MAX_QUERY_LENGTH = 100
        val LIMIT = 1..15
    }
}

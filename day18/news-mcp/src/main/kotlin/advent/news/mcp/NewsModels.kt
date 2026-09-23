package advent.news.mcp

import kotlinx.serialization.Serializable

/** Ответы сервиса новостей. Описаны один раз здесь — инструменты работают уже с готовыми объектами. */
@Serializable
data class Source(
    val key: String,
    val title: String,
    val url: String,
)

@Serializable
data class Subscription(
    val id: Long,
    /** feed — лента издания, topic — тема через Google News. */
    val kind: String,
    val target: String,
    val title: String,
    val everyMinutes: Int,
    val createdAt: String,
    val nextRunAt: String,
    val lastRunAt: String? = null,
    val lastAdded: Int? = null,
    val lastError: String? = null,
    val articles: Int,
)

@Serializable
data class RunResult(
    val found: Int,
    val added: Int,
    val error: String? = null,
)

@Serializable
data class SubscribeResult(
    val subscription: Subscription,
    val created: Boolean,
    val firstRun: RunResult? = null,
)

@Serializable
data class Digest(
    /** null, если сводка шла по курсору afterId. */
    val from: String? = null,
    val to: String,
    val minutes: Int? = null,
    val afterId: Long? = null,
    /** С него начинается следующая сводка без пропусков. */
    val cursor: Long,
    val query: String? = null,
    val subscriptionId: Long? = null,
    val total: Int,
    val bySource: List<SourceCount>,
    val bySubscription: List<SubscriptionCount>,
    val shown: Int,
    val headlines: List<Headline>,
)

@Serializable
data class SourceCount(
    val source: String,
    val count: Int,
)

@Serializable
data class SubscriptionCount(
    val subscriptionId: Long,
    val kind: String,
    val title: String,
    val count: Int,
)

@Serializable
data class Headline(
    val publishedAt: String,
    val sources: List<String>,
    val title: String,
    val link: String,
    val topics: List<String>,
)

@Serializable
data class FeedSubscriptionRequest(
    val source: String,
    val everyMinutes: Int? = null,
)

@Serializable
data class TopicSubscriptionRequest(
    val query: String,
    val everyMinutes: Int? = null,
)

/** Тело ошибки сервиса новостей. */
@Serializable
data class ApiError(val message: String)

package advent.pipeline.mcp

import kotlinx.serialization.Serializable

/** Ответы habr-service. Описаны один раз здесь — инструменты работают уже с готовыми объектами. */
@Serializable
data class Article(
    val id: String,
    val title: String,
    val link: String,
    val author: String? = null,
    val publishedAt: String? = null,
    val tags: List<String> = emptyList(),
    val text: String? = null,
)

@Serializable
data class SearchResult(
    val query: String? = null,
    val source: String,
    val fetchedAt: String,
    val count: Int,
    val items: List<Article>,
)

@Serializable
data class Source(
    val id: String,
    val title: String,
    val link: String,
)

@Serializable
data class Summary(
    val query: String? = null,
    val summary: String,
    val sources: List<Source>,
    val received: Int,
    val cited: Int,
)

@Serializable
data class Report(
    val id: Long,
    val createdAt: String,
    val query: String? = null,
    val summary: String,
    val sources: List<Source>,
    val cited: Int,
)

/** Тело ошибки habr-service. */
@Serializable
data class ApiError(val message: String)

package advent.habr.mcp

import kotlinx.serialization.Serializable

/** Ответы сервиса статей. Описаны один раз здесь — инструменты работают уже с готовыми объектами. */
@Serializable
data class Status(
    val enabled: Boolean,
    val source: String,
    val everyMinutes: Int,
    val nextRunAt: String? = null,
    val lastRun: LastRun? = null,
    val articles: Int,
    val retentionDays: Int,
)

@Serializable
data class LastRun(
    val at: String,
    val found: Int,
    val added: Int,
    val error: String? = null,
)

@Serializable
data class EnabledRequest(val enabled: Boolean)

@Serializable
data class Digest(
    /** null, если сводка шла по курсору afterId. */
    val from: String? = null,
    val to: String,
    val minutes: Int? = null,
    val afterId: Long? = null,
    /** С него начинается следующая сводка без пропусков. */
    val cursor: Long,
    val total: Int,
    val byCategory: List<CategoryCount>,
    val shown: Int,
    val articles: List<Article>,
)

@Serializable
data class CategoryCount(
    val category: String,
    val count: Int,
)

@Serializable
data class Article(
    val id: Long,
    val publishedAt: String,
    val title: String,
    val link: String,
    val author: String? = null,
    val categories: List<String>,
    val excerpt: String? = null,
)

/** Тело ошибки сервиса статей. */
@Serializable
data class ApiError(val message: String)

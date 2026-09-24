package advent.pipeline.web


/** Статья — единица данных, которая идёт по конвейеру от search к summarize. */
data class Article(
    /** Номер статьи на Хабре. По нему шаги сверяют, что статья дошла та же, и на него ссылается сводка. */
    val id: String,
    val title: String,
    val link: String,
    val author: String?,
    /** UTC, ISO-8601; null, если лента не дала даты. */
    val publishedAt: String?,
    val tags: List<String>,
    /** Начало статьи обычным текстом. */
    val text: String?,
)

/** Ответ GET /api/search. */
data class SearchResult(
    /** Тема поиска; null — свежая лента. */
    val query: String?,
    val source: String,
    val fetchedAt: String,
    val count: Int,
    val items: List<Article>,
)

/** Тело POST /api/summarize. */
data class SummarizeRequest(
    val query: String?,
    val items: List<ArticleInput>?,
)

data class ArticleInput(
    val id: String?,
    val title: String?,
    val link: String?,
    val author: String?,
    val publishedAt: String?,
    val tags: List<String>?,
    val text: String?,
)

/** Статья, на которую ссылается сводка. */
data class Source(
    val id: String,
    val title: String,
    val link: String,
)

/** Ответ POST /api/summarize. */
data class Summary(
    val query: String?,
    /** Текст сводки. Статьи в нём упомянуты номерами в квадратных скобках: [1082364]. */
    val summary: String,
    /** Все полученные статьи в том порядке, в каком пришли. */
    val sources: List<Source>,
    /** Сколько статей пришло на вход. */
    val received: Int,
    /** На сколько из них сводка сослалась. */
    val cited: Int,
)

/** Тело POST /api/reports. */
data class SaveReportRequest(
    val query: String?,
    val summary: String?,
    val sources: List<SourceInput>?,
)

data class SourceInput(
    val id: String?,
    val title: String?,
    val link: String?,
)

/** Сохранённый отчёт: ответ POST /api/reports и GET /api/reports/latest. */
data class Report(
    val id: Long,
    val createdAt: String,
    val query: String?,
    val summary: String,
    val sources: List<Source>,
    /** На сколько источников сводка ссылается. */
    val cited: Int,
)

data class ErrorResponse(val message: String)

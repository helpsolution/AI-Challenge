package advent.habr.web

/** Состояние сборщика: включён ли, как часто, когда следующий сбор и чем кончился последний. */
data class Status(
    val enabled: Boolean,
    val source: String,
    val everyMinutes: Int,
    /** Когда планировщик заберёт ленту в следующий раз, UTC; null — сбор выключен. */
    val nextRunAt: String?,
    /** null — ещё ни разу не собирали. */
    val lastRun: LastRun?,
    /** Сколько статей сейчас хранится. */
    val articles: Int,
    val retentionDays: Int,
)

data class LastRun(
    val at: String,
    /** Сколько статей было в ленте. */
    val found: Int,
    /** Сколько из них новых — остальные уже были в базе. */
    val added: Int,
    /** Причина неудачи; null — сбор прошёл. */
    val error: String?,
)

/** Тело PUT /api/enabled. Nullable, чтобы пустое тело дало понятную 400, а не false по умолчанию. */
data class EnabledRequest(val enabled: Boolean?)

/** Агрегированная сводка за период. */
data class Digest(
    /** Начало периода, UTC; null, если период задан курсором afterId. */
    val from: String?,
    val to: String,
    val minutes: Int?,
    val afterId: Long?,
    /** Курсор для следующей сводки: передайте его в afterId, и она начнётся ровно там, где кончилась эта. */
    val cursor: Long,
    val total: Int,
    /** Самые частые теги, по убыванию. */
    val byCategory: List<CategoryCount>,
    /** Сколько статей в списке: не больше limit. */
    val shown: Int,
    /** Статьи, новые сверху. */
    val articles: List<Article>,
)

data class CategoryCount(
    val category: String,
    val count: Int,
)

data class Article(
    val id: Long,
    val publishedAt: String,
    val title: String,
    val link: String,
    val author: String?,
    val categories: List<String>,
    val excerpt: String?,
)

data class ErrorResponse(val message: String)

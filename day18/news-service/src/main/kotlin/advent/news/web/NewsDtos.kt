package advent.news.web

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Лента из каталога, на которую можно подписаться")
data class Source(
    @field:Schema(description = "Ключ ленты — его передают при подписке", example = "tass")
    val key: String,
    @field:Schema(description = "Название издания", example = "ТАСС")
    val title: String,
    @field:Schema(description = "Адрес RSS", example = "https://tass.ru/rss/v2.xml")
    val url: String,
)

@Schema(description = "Подписка на ленту издания")
data class FeedSubscriptionRequest(
    @field:Schema(description = "Ключ ленты из /api/sources или название издания", example = "tass")
    val source: String,
    @field:Schema(description = "Как часто забирать ленту, в минутах: 1..1440. По умолчанию 10", example = "5")
    val everyMinutes: Int? = null,
)

@Schema(description = "Подписка на тему: поиск по всем изданиям через Google News")
data class TopicSubscriptionRequest(
    @field:Schema(description = "Тема — обычный поисковый запрос, 2..100 символов", example = "искусственный интеллект")
    val query: String,
    @field:Schema(description = "Как часто искать, в минутах: 1..1440. По умолчанию 10", example = "10")
    val everyMinutes: Int? = null,
)

@Schema(description = "Подписка: что забирать, как часто и чем закончился последний сбор")
data class Subscription(
    @field:Schema(description = "Идентификатор", example = "1")
    val id: Long,
    @field:Schema(description = "feed — лента издания, topic — тема через Google News", example = "feed")
    val kind: String,
    @field:Schema(description = "Ключ ленты или тема в нижнем регистре", example = "tass")
    val target: String,
    @field:Schema(description = "Название для человека", example = "ТАСС")
    val title: String,
    @field:Schema(description = "Интервал сбора в минутах", example = "5")
    val everyMinutes: Int,
    @field:Schema(description = "Момент создания в UTC", example = "2026-09-23T12:00:00Z")
    val createdAt: String,
    @field:Schema(description = "Когда планировщик заберёт ленту в следующий раз, UTC", example = "2026-09-23T12:05:00Z")
    val nextRunAt: String,
    @field:Schema(description = "Когда забирал в последний раз, UTC; null — ещё ни разу", example = "2026-09-23T12:00:00Z")
    val lastRunAt: String?,
    @field:Schema(description = "Сколько новых новостей принёс последний сбор", example = "7")
    val lastAdded: Int?,
    @field:Schema(description = "Чем закончился последний сбор, если неудачно; null — всё в порядке")
    val lastError: String?,
    @field:Schema(description = "Сколько новостей собрано по подписке и ещё хранится", example = "180")
    val articles: Int,
)

@Schema(description = "Итог одного сбора")
data class RunResult(
    @field:Schema(description = "Сколько новостей было в ленте", example = "100")
    val found: Int,
    @field:Schema(description = "Сколько из них новых — остальные уже были в базе", example = "7")
    val added: Int,
    @field:Schema(description = "Причина неудачи; null — сбор прошёл")
    val error: String?,
)

@Schema(description = "Результат подписки")
data class SubscribeResult(
    val subscription: Subscription,
    @field:Schema(description = "true — подписка новая; false — уже была, у неё обновился интервал")
    val created: Boolean,
    @field:Schema(description = "Первый сбор, сделанный сразу при подписке; null, если подписка уже была")
    val firstRun: RunResult?,
)

@Schema(description = "Агрегированная сводка за период")
data class Digest(
    @field:Schema(description = "Начало периода, UTC; null, если период задан курсором afterId", example = "2026-09-23T11:00:00Z")
    val from: String?,
    @field:Schema(description = "Момент сводки, UTC", example = "2026-09-23T12:00:00Z")
    val to: String,
    @field:Schema(description = "Период в минутах; null, если период задан курсором afterId", example = "60")
    val minutes: Int?,
    @field:Schema(description = "Курсор, после которого взяты новости, если сводка шла по курсору")
    val afterId: Long?,
    @field:Schema(
        description = "Курсор для следующей сводки: передайте его в afterId, и она начнётся ровно там, где кончилась эта",
        example = "1234",
    )
    val cursor: Long,
    @field:Schema(description = "Фильтр по заголовку, если задан")
    val query: String?,
    @field:Schema(description = "Фильтр по подписке, если задан")
    val subscriptionId: Long?,
    @field:Schema(description = "Сколько уникальных новостей за период: один заголовок из двух подписок считается один раз")
    val total: Int,
    @field:Schema(description = "Уникальные новости по изданиям, по убыванию")
    val bySource: List<SourceCount>,
    @field:Schema(description = "Новости по подпискам, по убыванию")
    val bySubscription: List<SubscriptionCount>,
    @field:Schema(description = "Сколько заголовков в списке: не больше limit")
    val shown: Int,
    @field:Schema(description = "Заголовки, новые сверху")
    val headlines: List<Headline>,
)

data class SourceCount(
    val source: String,
    val count: Int,
)

data class SubscriptionCount(
    val subscriptionId: Long,
    val kind: String,
    val title: String,
    val count: Int,
)

@Schema(description = "Новость в сводке")
data class Headline(
    @field:Schema(description = "Время публикации, UTC", example = "2026-09-23T11:58:00Z")
    val publishedAt: String,
    @field:Schema(description = "Издания, в которых вышел этот заголовок")
    val sources: List<String>,
    val title: String,
    val link: String,
    @field:Schema(description = "Темы подписок, по которым нашлась новость; пусто — пришла из ленты издания")
    val topics: List<String>,
)

@Schema(description = "Ошибка запроса")
data class ErrorResponse(
    @field:Schema(description = "Человекочитаемое объяснение", example = "Неизвестная лента «lenta»")
    val message: String,
)

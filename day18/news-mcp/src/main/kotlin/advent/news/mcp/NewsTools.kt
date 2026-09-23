package advent.news.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Шесть инструментов поверх REST API сервиса новостей.
 *
 * Отложенное выполнение устроено так: subscribe_* не собирает новости «сейчас и один раз»,
 * а заводит задание, которое сервис дальше выполняет сам, по расписанию, без участия модели.
 * news_digest потом отдаёт то, что накопилось, уже посчитанным.
 */
fun Server.registerNewsTools(api: NewsApi) {
    addTool(
        name = "list_sources",
        description = """
            Каталог лент изданий, на которые можно подписаться через subscribe_feed.
            Подписаться можно только на ленты из этого списка; любую другую тему ищи через subscribe_topic.
        """.trimIndent(),
        inputSchema = ToolSchema(properties = buildJsonObject {}),
        toolAnnotations = ToolAnnotations(
            title = "Каталог лент",
            readOnlyHint = true,
            idempotentHint = true,
            openWorldHint = false,
        ),
    ) {
        respond {
            val sources = api.sources()
            sources.joinToString("\n") { "${it.key} — ${it.title}" }
        }
    }

    addTool(
        name = "subscribe_feed",
        description = """
            Подписаться на ленту издания из каталога list_sources. Сервер сразу собирает ленту первый раз,
            а дальше сам, по расписанию, забирает её каждые every_minutes минут и складывает новости в базу.
            Повторная подписка на ту же ленту не создаёт дубль, а меняет интервал.
        """.trimIndent(),
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("source") {
                    put("type", "string")
                    put("description", "Ключ ленты из list_sources (например \"tass\") или название издания (\"ТАСС\")")
                    put("minLength", 1)
                }
                putJsonObject("every_minutes") { everyMinutesSchema() }
            },
            required = listOf("source"),
        ),
        toolAnnotations = ToolAnnotations(
            title = "Подписаться на ленту",
            readOnlyHint = false,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true,
        ),
    ) { request ->
        val source = request.stringArgument("source")
            ?: return@addTool failure("Не передан обязательный аргумент source")
        respond { describe(api.subscribeFeed(source, request.intArgument("every_minutes"))) }
    }

    addTool(
        name = "subscribe_topic",
        description = """
            Подписаться на тему: сервер ищет её по всем изданиям через Google News за последние сутки,
            сразу первый раз и дальше сам каждые every_minutes минут. Подходит для всего, чего нет в каталоге лент:
            «искусственный интеллект», «ключевая ставка», «Formula 1».
        """.trimIndent(),
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Тема как поисковый запрос, 2..100 символов, например \"искусственный интеллект\"")
                    put("minLength", 2)
                    put("maxLength", 100)
                }
                putJsonObject("every_minutes") { everyMinutesSchema() }
            },
            required = listOf("query"),
        ),
        toolAnnotations = ToolAnnotations(
            title = "Подписаться на тему",
            readOnlyHint = false,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true,
        ),
    ) { request ->
        val query = request.stringArgument("query")
            ?: return@addTool failure("Не передан обязательный аргумент query")
        respond { describe(api.subscribeTopic(query, request.intArgument("every_minutes"))) }
    }

    addTool(
        name = "list_subscriptions",
        description = """
            Все подписки с расписанием: как часто собираются, когда был последний сбор и сколько он принёс,
            когда следующий, сколько новостей хранится и была ли ошибка. Здесь же id для unsubscribe и news_digest.
        """.trimIndent(),
        inputSchema = ToolSchema(properties = buildJsonObject {}),
        toolAnnotations = ToolAnnotations(
            title = "Подписки",
            readOnlyHint = true,
            idempotentHint = true,
            openWorldHint = false,
        ),
    ) {
        respond {
            val subscriptions = api.subscriptions()
            if (subscriptions.isEmpty()) {
                "Подписок нет. Ленты изданий — list_sources и subscribe_feed, любые темы — subscribe_topic."
            } else {
                "Подписок: ${subscriptions.size}\n" + subscriptions.joinToString("\n", transform = ::describe)
            }
        }
    }

    addTool(
        name = "unsubscribe",
        description = """
            Удалить подписку по id из list_subscriptions. Удаляются и все новости, собранные по ней, — это необратимо.
        """.trimIndent(),
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("subscription_id") {
                    put("type", "integer")
                    put("description", "id подписки из list_subscriptions")
                    put("minimum", 1)
                }
            },
            required = listOf("subscription_id"),
        ),
        toolAnnotations = ToolAnnotations(
            title = "Отписаться",
            readOnlyHint = false,
            destructiveHint = true,
            idempotentHint = true,
            openWorldHint = false,
        ),
    ) { request ->
        val id = request.longArgument("subscription_id")
            ?: return@addTool failure("Не передан обязательный аргумент subscription_id")
        respond {
            api.unsubscribe(id)
            "Подписка #$id удалена вместе с собранными по ней новостями"
        }
    }

    addTool(
        name = "news_digest",
        description = """
            Агрегированная сводка по уже собранным новостям за последние minutes минут: сколько новостей,
            из каких изданий и по каким подпискам, и сами заголовки без повторов, новые сверху.
            Сервер ничего не скачивает в момент вызова — он отдаёт то, что планировщик собрал по подпискам.
            query оставляет только новости, где в заголовке есть слово, начинающееся с этого текста:
            "трамп" найдёт «Трампа», но короткий фрагмент внутри слова не сработает.
            Для сводок подряд без пропусков вместо minutes передают after_id — cursor из прошлой сводки:
            тогда придёт всё, что собрано после неё, даже если лента опубликовала новость с опозданием.
        """.trimIndent(),
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("minutes") {
                    put("type", "integer")
                    put("description", "За сколько последних минут, по времени публикации: от 1 до 10080 (неделя)")
                    put("minimum", 1)
                    put("maximum", 10080)
                    put("default", DEFAULT_DIGEST_MINUTES)
                }
                putJsonObject("after_id") {
                    put("type", "integer")
                    put("description", "Вместо minutes: cursor прошлой сводки — придёт всё, что собрано после неё")
                    put("minimum", 0)
                }
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Необязательный фильтр по слову в заголовке, например \"ставк\" или \"OpenAI\"")
                    put("maxLength", 100)
                }
                putJsonObject("subscription_id") {
                    put("type", "integer")
                    put("description", "Необязательно: только новости одной подписки из list_subscriptions")
                    put("minimum", 1)
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Сколько заголовков вернуть, от 1 до 300")
                    put("minimum", 1)
                    put("maximum", 300)
                    put("default", DEFAULT_DIGEST_LIMIT)
                }
                putJsonObject("with_links") {
                    put("type", "boolean")
                    put("description", "Добавить ссылки к заголовкам. Нужно, только если пользователь просит ссылки")
                    put("default", false)
                }
            },
        ),
        outputSchema = DIGEST_OUTPUT_SCHEMA,
        toolAnnotations = ToolAnnotations(
            title = "Сводка новостей",
            readOnlyHint = true,
            idempotentHint = true,
            openWorldHint = false,
        ),
    ) { request ->
        respond(
            structured = { digest -> STRUCTURED_JSON.encodeToJsonElement(Digest.serializer(), digest).jsonObject },
            fetch = {
                val afterId = request.longArgument("after_id")
                api.digest(
                    minutes = request.intArgument("minutes") ?: DEFAULT_DIGEST_MINUTES.takeIf { afterId == null },
                    afterId = afterId,
                    query = request.stringArgument("query"),
                    subscriptionId = request.longArgument("subscription_id"),
                    limit = request.intArgument("limit") ?: DEFAULT_DIGEST_LIMIT,
                )
            },
            text = { digest -> describe(digest, withLinks = request.booleanArgument("with_links") == true) },
        )
    }
}

private const val DEFAULT_DIGEST_MINUTES = 60
private const val DEFAULT_DIGEST_LIMIT = 100

/** Разбивку по изданиям режем: Google News приносит десятки мелких сайтов, и хвост из них модели не нужен. */
private const val TOP_SOURCES = 10

/**
 * Время показывается в часовом поясе сервера: локально это пояс машины, в контейнере — переменная TZ.
 * Хранится и передаётся по API оно в UTC.
 */
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/**
 * Незаданные поля (from у сводки по курсору) в structuredContent не попадают вовсе:
 * null не прошёл бы проверку по outputSchema, где from объявлен строкой.
 */
@OptIn(ExperimentalSerializationApi::class)
private val STRUCTURED_JSON = Json {
    encodeDefaults = true
    explicitNulls = false
}

private fun JsonObjectBuilder.everyMinutesSchema() {
    put("type", "integer")
    put("description", "Как часто собирать, в минутах: от 1 до 1440. По умолчанию 10")
    put("minimum", 1)
    put("maximum", 1440)
    put("default", 10)
}

private fun describe(result: SubscribeResult): String {
    val subscription = result.subscription
    val name = subscription.labelAfterNa()
    val next = "Следующий сбор в ${clock(subscription.nextRunAt)}."
    val firstRun = result.firstRun
    return when {
        !result.created ->
            "Подписка #${subscription.id} на $name уже была — интервал теперь ${subscription.everyMinutes} мин. $next"

        firstRun?.error != null ->
            "Подписка #${subscription.id} на $name создана, каждые ${subscription.everyMinutes} мин. " +
                "Первый сбор не удался: ${firstRun.error}. $next"

        else ->
            "Подписка #${subscription.id} на $name создана, каждые ${subscription.everyMinutes} мин. " +
                "Первый сбор: в ленте ${firstRun?.found ?: 0}, записано новых ${firstRun?.added ?: 0}. $next"
    }
}

private fun describe(subscription: Subscription): String = buildString {
    append("#${subscription.id} ${subscription.label()} · каждые ${subscription.everyMinutes} мин")
    val lastRun = subscription.lastRunAt
    if (lastRun == null) {
        append(" · ещё не собиралась")
    } else {
        append(" · последний сбор ${clock(lastRun)}")
        subscription.lastAdded?.let { append(", новых $it") }
    }
    append(" · следующий ${clock(subscription.nextRunAt)}")
    append(" · в базе ${subscription.articles}")
    subscription.lastError?.let { append(" · ошибка: $it") }
}

/**
 * Текст для модели: сначала готовые цифры, потом заголовки.
 * Цифры считает сервис, а не модель, — пересчитывать сотню строк на глаз ей не нужно.
 */
private fun describe(digest: Digest, withLinks: Boolean): String = buildString {
    val period = if (digest.from != null) {
        "за ${clock(digest.from)}–${clock(digest.to)} (${digest.minutes} мин)"
    } else {
        "после after_id=${digest.afterId} (до ${clock(digest.to)})"
    }
    val filters = listOfNotNull(
        digest.query?.let { "фильтр «$it»" },
        digest.subscriptionId?.let { "подписка #$it" },
    ).joinToString(", ").let { if (it.isEmpty()) "" else ", $it" }

    // Курсор нужен модели, только если она сама захочет продолжить с этого места.
    val next = "Курсор для следующей сводки: after_id=${digest.cursor}."

    if (digest.total == 0) {
        append("Новостей $period$filters нет. $next")
        return@buildString
    }

    appendLine("Сводка $period$filters: ${digest.total} ${plural(digest.total, "новость", "новости", "новостей")}.")

    val top = digest.bySource.take(TOP_SOURCES).joinToString(", ") { "${it.source} — ${it.count}" }
    val rest = digest.bySource.drop(TOP_SOURCES)
    append("По изданиям: $top")
    if (rest.isNotEmpty()) {
        append(", ещё ${rest.size} ${plural(rest.size, "издание", "издания", "изданий")} — ${rest.sumOf { it.count }}")
    }
    appendLine(".")

    appendLine(
        "По подпискам: " + digest.bySubscription.joinToString(", ") {
            val name = if (it.kind == KIND_TOPIC) "тема «${it.title}»" else it.title
            "#${it.subscriptionId} $name — ${it.count}"
        } + ".",
    )

    appendLine("Заголовки, новые сверху (показано ${digest.shown} из ${digest.total}):")
    digest.headlines.forEach { headline ->
        append("${clock(headline.publishedAt)} · ${headline.sources.joinToString(", ")} · ${headline.title}")
        if (headline.topics.isNotEmpty()) append(" [тема: ${headline.topics.joinToString(", ")}]")
        if (withLinks) append(" — ${headline.link}")
        appendLine()
    }
    append(next)
}

private const val KIND_TOPIC = "topic"

/** Как подписку называть в списке: «ТАСС», «тема «ИИ»». */
private fun Subscription.label(): String = if (kind == KIND_TOPIC) "тема «$title»" else title

/** То же после «подписка на»: «на ТАСС», «на тему «ИИ»». */
private fun Subscription.labelAfterNa(): String = if (kind == KIND_TOPIC) "тему «$title»" else title

private fun clock(instant: String): String = CLOCK.format(Instant.parse(instant))

private fun plural(n: Int, one: String, few: String, many: String): String {
    val lastTwo = n % 100
    val last = n % 10
    return when {
        lastTwo in 11..14 -> many
        last == 1 -> one
        last in 2..4 -> few
        else -> many
    }
}

/** Схема structuredContent у news_digest: по ней код агента берёт total, не разбирая текст. */
private val DIGEST_OUTPUT_SCHEMA = ToolSchema(
    properties = buildJsonObject {
        putJsonObject("from") {
            put("type", "string")
            put("description", "Начало периода, UTC, ISO-8601; нет, если сводка шла по курсору")
        }
        putJsonObject("to") {
            put("type", "string")
            put("description", "Конец периода, UTC, ISO-8601")
        }
        putJsonObject("cursor") {
            put("type", "integer")
            put("description", "Курсор для следующей сводки: его передают в after_id")
        }
        putJsonObject("total") {
            put("type", "integer")
            put("description", "Сколько уникальных новостей за период")
        }
        putJsonObject("shown") {
            put("type", "integer")
            put("description", "Сколько заголовков в headlines")
        }
        putJsonObject("bySource") {
            put("type", "array")
            put("description", "Уникальные новости по изданиям, по убыванию")
            putJsonObject("items") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("source") { put("type", "string") }
                    putJsonObject("count") { put("type", "integer") }
                }
            }
        }
        putJsonObject("headlines") {
            put("type", "array")
            put("description", "Заголовки, новые сверху")
            putJsonObject("items") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("publishedAt") { put("type", "string") }
                    putJsonObject("sources") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                    }
                    putJsonObject("title") { put("type", "string") }
                    putJsonObject("link") { put("type", "string") }
                }
            }
        }
    },
    required = listOf("to", "cursor", "total", "shown", "bySource", "headlines"),
)

/** Ошибку сервиса отдаём как ошибку инструмента, а не как падение соединения: модель увидит причину и сможет исправиться. */
private suspend fun respond(block: suspend () -> String): CallToolResult = try {
    CallToolResult(content = listOf(TextContent(block())))
} catch (e: NewsApiException) {
    failure(e.message ?: "Сервис новостей недоступен")
}

/**
 * Результат сразу в двух видах: текстом для модели и структурой в structuredContent —
 * по ней вызывающий код берёт числа из ответа, а не разбирает строку.
 */
private suspend fun <T> respond(
    fetch: suspend () -> T,
    text: (T) -> String,
    structured: (T) -> JsonObject,
): CallToolResult = try {
    val value = fetch()
    CallToolResult(content = listOf(TextContent(text(value))), structuredContent = structured(value))
} catch (e: NewsApiException) {
    failure(e.message ?: "Сервис новостей недоступен")
}

private fun failure(message: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(message)), isError = true)

/** Модель иногда шлёт необязательный аргумент как null — это «не задан», а не строка "null". */
private fun CallToolRequest.primitive(name: String): JsonPrimitive? =
    (arguments?.get(name) as? JsonPrimitive)?.takeUnless { it is JsonNull }

private fun CallToolRequest.stringArgument(name: String): String? =
    primitive(name)?.content?.takeIf { it.isNotBlank() }

private fun CallToolRequest.intArgument(name: String): Int? = primitive(name)?.intOrNull

private fun CallToolRequest.longArgument(name: String): Long? = primitive(name)?.longOrNull

private fun CallToolRequest.booleanArgument(name: String): Boolean? = primitive(name)?.booleanOrNull

package advent.habr.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
 * Три инструмента поверх REST API сервиса статей.
 *
 * Периодическое выполнение живёт не в инструменте, а в сервисе: он сам, по расписанию, забирает
 * ленту Хабра и складывает статьи в SQLite. news_set_enabled включает и выключает это расписание,
 * а news_digest отдаёт накопленное уже посчитанным.
 */
fun Server.registerNewsTools(api: NewsApi) {
    addTool(
        name = "news_status",
        description = """
            Состояние сборщика статей Хабра: включён ли он, как часто забирает ленту, когда был последний сбор
            и сколько он принёс, когда следующий и сколько статей хранится.
        """.trimIndent(),
        inputSchema = ToolSchema(properties = buildJsonObject {}),
        outputSchema = STATUS_OUTPUT_SCHEMA,
        toolAnnotations = ToolAnnotations(
            title = "Состояние сборщика",
            readOnlyHint = true,
            idempotentHint = true,
            openWorldHint = false,
        ),
    ) {
        respond(fetch = { api.status() }, serializer = Status.serializer(), text = ::describe)
    }

    addTool(
        name = "news_set_enabled",
        description = """
            Включить или выключить сбор статей Хабра по расписанию. При включении сервер сразу делает первый сбор,
            не дожидаясь расписания. Выключение не удаляет уже собранные статьи. Повторный вызов с тем же значением ничего не меняет.
        """.trimIndent(),
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("enabled") {
                    put("type", "boolean")
                    put("description", "true — собирать по расписанию, false — не собирать")
                }
            },
            required = listOf("enabled"),
        ),
        outputSchema = STATUS_OUTPUT_SCHEMA,
        toolAnnotations = ToolAnnotations(
            title = "Включить или выключить сбор",
            readOnlyHint = false,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true,
        ),
    ) { request ->
        val enabled = request.primitive("enabled")?.booleanOrNull
            ?: return@addTool failure("Не передан обязательный аргумент enabled: true или false")
        respond(fetch = { api.setEnabled(enabled) }, serializer = Status.serializer(), text = ::describe)
    }

    addTool(
        name = "news_digest",
        description = """
            Агрегированная сводка по уже собранным статьям Хабра за последние minutes минут: сколько статей,
            самые частые теги и сами статьи — заголовок, автор, теги и начало текста, новые сверху, с номерами [1], [2], ….
            Сервер ничего не скачивает в момент вызова — он отдаёт то, что планировщик собрал по расписанию.
            Для сводок подряд без пропусков вместо minutes передают after_id — cursor из прошлой сводки:
            тогда придёт всё, что собрано после неё.
        """.trimIndent(),
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("minutes") {
                    put("type", "integer")
                    put("description", "За сколько последних минут, по времени публикации: от 1 до 43200 (30 дней)")
                    put("minimum", 1)
                    put("maximum", 43200)
                    put("default", DEFAULT_DIGEST_MINUTES)
                }
                putJsonObject("after_id") {
                    put("type", "integer")
                    put("description", "Вместо minutes: cursor прошлой сводки — придёт всё, что собрано после неё")
                    put("minimum", 0)
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Сколько статей вернуть, от 1 до 300")
                    put("minimum", 1)
                    put("maximum", 300)
                    put("default", DEFAULT_DIGEST_LIMIT)
                }
                putJsonObject("with_links") {
                    put("type", "boolean")
                    put("description", "Добавить ссылки к статьям. Нужно, только если пользователь просит ссылки")
                    put("default", false)
                }
            },
        ),
        outputSchema = DIGEST_OUTPUT_SCHEMA,
        toolAnnotations = ToolAnnotations(
            title = "Сводка статей Хабра",
            readOnlyHint = true,
            idempotentHint = true,
            openWorldHint = false,
        ),
    ) { request ->
        val afterId = request.primitive("after_id")?.longOrNull
        val withLinks = request.primitive("with_links")?.booleanOrNull == true
        respond(
            fetch = {
                api.digest(
                    minutes = request.primitive("minutes")?.intOrNull ?: DEFAULT_DIGEST_MINUTES.takeIf { afterId == null },
                    afterId = afterId,
                    limit = request.primitive("limit")?.intOrNull ?: DEFAULT_DIGEST_LIMIT,
                )
            },
            serializer = Digest.serializer(),
            text = { describe(it, withLinks) },
        )
    }
}

private const val DEFAULT_DIGEST_MINUTES = 24 * 60
private const val DEFAULT_DIGEST_LIMIT = 100

/**
 * Время показывается в часовом поясе сервера: локально это пояс машины, на VPS — переменная TZ из юнита.
 * Хранится и передаётся по API оно в UTC.
 */
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val DATE_CLOCK = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault())

/**
 * Незаданные поля (from у сводки по курсору) в structuredContent не попадают вовсе:
 * null не прошёл бы проверку по outputSchema, где from объявлен строкой.
 */
@OptIn(ExperimentalSerializationApi::class)
private val STRUCTURED_JSON = Json {
    encodeDefaults = true
    explicitNulls = false
}

private fun describe(status: Status): String = buildString {
    appendLine(if (status.enabled) "Сбор включён: ${status.source} раз в ${status.everyMinutes} мин." else "Сбор выключен.")
    val lastRun = status.lastRun
    when {
        lastRun == null -> appendLine("Ещё ни разу не собирали.")
        lastRun.error != null -> appendLine("Последний сбор ${dateClock(lastRun.at)} не удался: ${lastRun.error}.")
        else -> appendLine("Последний сбор ${dateClock(lastRun.at)}: в ленте ${lastRun.found}, новых ${lastRun.added}.")
    }
    status.nextRunAt?.let { appendLine("Следующий сбор в ${clock(it)}.") }
    append("В базе ${status.articles} ${plural(status.articles, "статья", "статьи", "статей")}, храним ${status.retentionDays} дней.")
}

/**
 * Текст для модели: сначала готовые цифры, потом пронумерованные статьи.
 * Номера нужны, чтобы модель ссылалась на статью, не переписывая адрес: ссылки подставит код по structuredContent.
 */
private fun describe(digest: Digest, withLinks: Boolean): String = buildString {
    val period = if (digest.from != null) {
        "за ${dateClock(digest.from)}–${clock(digest.to)} (${digest.minutes} мин)"
    } else {
        "после after_id=${digest.afterId} (до ${clock(digest.to)})"
    }
    val next = "Курсор для следующей сводки: after_id=${digest.cursor}."

    if (digest.total == 0) {
        append("Статей $period нет. $next")
        return@buildString
    }

    appendLine("Хабр $period: ${digest.total} ${plural(digest.total, "статья", "статьи", "статей")}.")
    if (digest.byCategory.isNotEmpty()) {
        appendLine("Частые теги: " + digest.byCategory.joinToString(", ") { "${it.category} — ${it.count}" } + ".")
    }
    appendLine("Статьи, новые сверху (показано ${digest.shown} из ${digest.total}):")
    digest.articles.forEachIndexed { index, article ->
        append("[${index + 1}] ${clock(article.publishedAt)} · ${article.title}")
        article.author?.let { append(" · автор $it") }
        if (article.categories.isNotEmpty()) append(" · теги: ${article.categories.joinToString(", ")}")
        if (withLinks) append(" · ${article.link}")
        appendLine()
        article.excerpt?.let { appendLine("    $it") }
    }
    append(next)
}

private fun clock(instant: String): String = CLOCK.format(Instant.parse(instant))

private fun dateClock(instant: String): String = DATE_CLOCK.format(Instant.parse(instant))

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

/** Схема structuredContent у news_status и news_set_enabled: по ней бот берёт enabled, не разбирая текст. */
private val STATUS_OUTPUT_SCHEMA = ToolSchema(
    properties = buildJsonObject {
        putJsonObject("enabled") { put("type", "boolean") }
        putJsonObject("source") { put("type", "string") }
        putJsonObject("everyMinutes") { put("type", "integer") }
        putJsonObject("nextRunAt") {
            put("type", "string")
            put("description", "Следующий сбор, UTC; нет, если сбор выключен")
        }
        putJsonObject("lastRun") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("at") { put("type", "string") }
                putJsonObject("found") { put("type", "integer") }
                putJsonObject("added") { put("type", "integer") }
                putJsonObject("error") { put("type", "string") }
            }
        }
        putJsonObject("articles") { put("type", "integer") }
        putJsonObject("retentionDays") { put("type", "integer") }
    },
    required = listOf("enabled", "source", "everyMinutes", "articles", "retentionDays"),
)

/** Схема structuredContent у news_digest: по ней бот подставляет ссылки на статьи по их номерам. */
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
        putJsonObject("total") { put("type", "integer") }
        putJsonObject("shown") { put("type", "integer") }
        putJsonObject("byCategory") {
            put("type", "array")
            putJsonObject("items") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("category") { put("type", "string") }
                    putJsonObject("count") { put("type", "integer") }
                }
            }
        }
        putJsonObject("articles") {
            put("type", "array")
            put("description", "Статьи, новые сверху; статья [n] из текста — элемент n-1")
            putJsonObject("items") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("publishedAt") { put("type", "string") }
                    putJsonObject("title") { put("type", "string") }
                    putJsonObject("link") { put("type", "string") }
                    putJsonObject("author") { put("type", "string") }
                }
            }
        }
    },
    required = listOf("to", "cursor", "total", "shown", "byCategory", "articles"),
)

/**
 * Результат сразу в двух видах: текстом для модели и структурой в structuredContent —
 * по ней вызывающий код берёт числа и ссылки из ответа, а не разбирает строку.
 * Ошибку сервиса отдаём как ошибку инструмента, а не как падение соединения.
 */
private suspend fun <T> respond(
    fetch: suspend () -> T,
    serializer: KSerializer<T>,
    text: (T) -> String,
): CallToolResult = try {
    val value = fetch()
    CallToolResult(
        content = listOf(TextContent(text(value))),
        structuredContent = STRUCTURED_JSON.encodeToJsonElement(serializer, value).jsonObject,
    )
} catch (e: NewsApiException) {
    failure(e.message ?: "Сервис статей недоступен")
}

private fun failure(message: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(message)), isError = true)

/** Модель иногда шлёт необязательный аргумент как null — это «не задан», а не строка "null". */
private fun CallToolRequest.primitive(name: String): JsonPrimitive? =
    (arguments?.get(name) as? JsonPrimitive)?.takeUnless { it is JsonNull }

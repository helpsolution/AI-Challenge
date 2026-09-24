package advent.pipeline.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Четыре инструмента конвейера — по одному на запрос habr-service:
 *
 *   habr_search → habr_summarize → report_save,   и отдельно report_latest.
 *
 * Данные между шагами передаёт модель: результат одного инструмента она кладёт в аргументы
 * следующего. Поэтому текст каждого результата — это JSON, который можно переложить дальше
 * как есть, и подсказка, куда именно его переложить.
 */
fun Server.registerPipelineTools(api: PipelineApi) {
    addTool(
        name = "habr_search",
        description = """
            Шаг 1 конвейера: найти статьи Хабра. С query — поиск по теме, без query — свежая лента. Новые сверху.
            Возвращает items — статьи целиком: id, заголовок, ссылка, автор, дата, теги и начало текста.
            Чтобы сделать сводку, передай items в habr_summarize без изменений.
        """.trimIndent(),
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Тема поиска, до 100 символов, например «RAG» или «Kotlin Multiplatform». Не задана — свежая лента")
                    put("maxLength", 100)
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Сколько статей вернуть, от 1 до 15")
                    put("minimum", 1)
                    put("maximum", 15)
                    put("default", DEFAULT_LIMIT)
                }
            },
        ),
        outputSchema = SEARCH_OUTPUT_SCHEMA,
        toolAnnotations = ToolAnnotations(
            title = "Поиск статей Хабра",
            readOnlyHint = true,
            idempotentHint = false,
            openWorldHint = true,
        ),
    ) { request ->
        val query = request.string("query")
        val limit = request.primitive("limit")?.intOrNull ?: DEFAULT_LIMIT
        respond(fetch = { api.search(query, limit) }, serializer = SearchResult.serializer(), text = ::describe)
    }

    addTool(
        name = "habr_summarize",
        description = """
            Шаг 2 конвейера: сделать сводку по статьям. На вход — items из habr_search целиком, без изменений:
            сервер сверяет статьи по id и пишет сводку только по тому, что пришло.
            Возвращает summary — текст сводки со ссылками на статьи вида [1082364] — и sources — список статей.
            Чтобы сохранить, передай summary и sources в report_save без изменений.
        """.trimIndent(),
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Та же тема, что в habr_search; не задана — свежая лента")
                }
                putJsonObject("items") {
                    put("type", "array")
                    put("description", "Статьи из habr_search: массив items целиком, от 1 до 20 элементов")
                    put("minItems", 1)
                    put("maxItems", 20)
                    put("items", ARTICLE_SCHEMA)
                }
            },
            required = listOf("items"),
        ),
        outputSchema = SUMMARY_OUTPUT_SCHEMA,
        toolAnnotations = ToolAnnotations(
            title = "Сводка по статьям",
            readOnlyHint = true,
            idempotentHint = false,
            openWorldHint = true,
        ),
    ) { request ->
        val items = request.arguments?.get("items") as? JsonArray
            ?: return@addTool failure("Не передан обязательный аргумент items: массив статей из habr_search")
        val body = buildJsonObject {
            putQuery(request)
            put("items", items)
        }
        respond(fetch = { api.summarize(body) }, serializer = Summary.serializer(), text = ::describe)
    }

    addTool(
        name = "report_save",
        description = """
            Шаг 3 конвейера: сохранить сводку как отчёт. На вход — summary и sources из habr_summarize без изменений.
            Сервер отклонит отчёт, если сводка ссылается на статью, которой нет в sources.
            Возвращает номер и время сохранённого отчёта.
        """.trimIndent(),
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Та же тема, что в habr_search; не задана — свежая лента")
                }
                putJsonObject("summary") {
                    put("type", "string")
                    put("description", "Текст сводки из habr_summarize, без изменений")
                }
                putJsonObject("sources") {
                    put("type", "array")
                    put("description", "Источники из habr_summarize, без изменений")
                    put("minItems", 1)
                    put("maxItems", 20)
                    put("items", SOURCE_SCHEMA)
                }
            },
            required = listOf("summary", "sources"),
        ),
        outputSchema = REPORT_OUTPUT_SCHEMA,
        toolAnnotations = ToolAnnotations(
            title = "Сохранить отчёт",
            readOnlyHint = false,
            destructiveHint = false,
            idempotentHint = false,
            openWorldHint = false,
        ),
    ) { request ->
        val summary = request.string("summary")
            ?: return@addTool failure("Не передан обязательный аргумент summary: текст сводки из habr_summarize")
        val sources = request.arguments?.get("sources") as? JsonArray
            ?: return@addTool failure("Не передан обязательный аргумент sources: источники из habr_summarize")
        val body = buildJsonObject {
            putQuery(request)
            put("summary", summary)
            put("sources", sources)
        }
        respond(fetch = { api.save(body) }, serializer = Report.serializer(), text = ::describeSaved)
    }

    addTool(
        name = "report_latest",
        description = """
            Последний сохранённый отчёт: номер, время, тема, текст сводки и источники.
            Ничего не ищет и не пересчитывает — отдаёт то, что сохранил report_save.
        """.trimIndent(),
        inputSchema = ToolSchema(properties = buildJsonObject {}),
        outputSchema = REPORT_OUTPUT_SCHEMA,
        toolAnnotations = ToolAnnotations(
            title = "Последний отчёт",
            readOnlyHint = true,
            idempotentHint = true,
            openWorldHint = false,
        ),
    ) {
        respond(fetch = { api.latest() }, serializer = Report.serializer(), text = ::describeLatest)
    }
}

private const val DEFAULT_LIMIT = 10

/**
 * Незаданные поля (автор, тема) в JSON не попадают вовсе: null не прошёл бы проверку по outputSchema,
 * где поле объявлено строкой, и модели меньше переписывать.
 */
@OptIn(ExperimentalSerializationApi::class)
private val JSON = Json {
    encodeDefaults = true
    explicitNulls = false
}

/** Время показывается в часовом поясе сервера: локально это пояс машины, на VPS — переменная TZ из юнита. */
private val DATE_CLOCK = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault())

private fun describe(result: SearchResult): String = buildString {
    val topic = result.query?.let { " «$it»" }.orEmpty()
    if (result.count == 0) {
        append("${result.source}$topic: ничего не найдено. Сводку делать не из чего — попробуй другую тему.")
        return@buildString
    }
    appendLine("${result.source}$topic: ${result.count} ${plural(result.count, "статья", "статьи", "статей")}.")
    appendLine("Следующий шаг — habr_summarize: передай этот массив items целиком и без изменений.")
    append("items: ").append(JSON.encodeToString(ListSerializer(Article.serializer()), result.items))
}

private fun describe(summary: Summary): String = buildString {
    appendLine("Сводка готова: статей на входе ${summary.received}, сводка ссылается на ${summary.cited}.")
    appendLine("Следующий шаг — report_save: передай summary и sources ровно как здесь, без изменений.")
    append(
        JSON.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                summary.query?.let { put("query", it) }
                put("summary", summary.summary)
                put("sources", JSON.encodeToJsonElement(ListSerializer(Source.serializer()), summary.sources))
            },
        ),
    )
}

private fun describeSaved(report: Report): String =
    "Отчёт #${report.id} сохранён ${dateClock(report.createdAt)}: источников ${report.sources.size}, " +
        "сводка ссылается на ${report.cited}."

private fun describeLatest(report: Report): String = buildString {
    val topic = report.query?.let { "тема «$it»" } ?: "свежая лента"
    appendLine("Отчёт #${report.id} от ${dateClock(report.createdAt)}, $topic.")
    appendLine()
    appendLine(report.summary)
    appendLine()
    appendLine("Источники:")
    report.sources.forEach { appendLine("[${it.id}] ${it.title} — ${it.link}") }
}.trimEnd()

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

private fun JsonObjectBuilder.putQuery(request: CallToolRequest) {
    request.string("query")?.let { put("query", it) }
}

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
        structuredContent = JSON.encodeToJsonElement(serializer, value).jsonObject,
    )
} catch (e: PipelineApiException) {
    failure(e.message ?: "habr-service недоступен")
}

private fun failure(message: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(message)), isError = true)

/** Модель иногда шлёт необязательный аргумент как null — это «не задан», а не строка "null". */
private fun CallToolRequest.primitive(name: String): JsonPrimitive? =
    (arguments?.get(name) as? JsonPrimitive)?.takeUnless { it is JsonNull }

private fun CallToolRequest.string(name: String): String? =
    primitive(name)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

private val SOURCE_SCHEMA = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("id") { put("type", "string") }
        putJsonObject("title") { put("type", "string") }
        putJsonObject("link") { put("type", "string") }
    }
    putJsonArray("required") { add("id"); add("title"); add("link") }
}

private val ARTICLE_SCHEMA = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("id") { put("type", "string") }
        putJsonObject("title") { put("type", "string") }
        putJsonObject("link") { put("type", "string") }
        putJsonObject("author") { put("type", "string") }
        putJsonObject("publishedAt") { put("type", "string") }
        putJsonObject("tags") {
            put("type", "array")
            putJsonObject("items") { put("type", "string") }
        }
        putJsonObject("text") { put("type", "string") }
    }
    putJsonArray("required") { add("id"); add("title"); add("link") }
}

/** Схемы structuredContent: по ним бот берёт статьи и отчёт из ответа, не разбирая текст. */
private val SEARCH_OUTPUT_SCHEMA = ToolSchema(
    properties = buildJsonObject {
        putJsonObject("query") { put("type", "string") }
        putJsonObject("source") { put("type", "string") }
        putJsonObject("fetchedAt") { put("type", "string") }
        putJsonObject("count") { put("type", "integer") }
        putJsonObject("items") {
            put("type", "array")
            put("items", ARTICLE_SCHEMA)
        }
    },
    required = listOf("source", "fetchedAt", "count", "items"),
)

private val SUMMARY_OUTPUT_SCHEMA = ToolSchema(
    properties = buildJsonObject {
        putJsonObject("query") { put("type", "string") }
        putJsonObject("summary") { put("type", "string") }
        putJsonObject("sources") {
            put("type", "array")
            put("items", SOURCE_SCHEMA)
        }
        putJsonObject("received") { put("type", "integer") }
        putJsonObject("cited") { put("type", "integer") }
    },
    required = listOf("summary", "sources", "received", "cited"),
)

private val REPORT_OUTPUT_SCHEMA = ToolSchema(
    properties = buildJsonObject {
        putJsonObject("id") { put("type", "integer") }
        putJsonObject("createdAt") { put("type", "string") }
        putJsonObject("query") { put("type", "string") }
        putJsonObject("summary") { put("type", "string") }
        putJsonObject("sources") {
            put("type", "array")
            put("items", SOURCE_SCHEMA)
        }
        putJsonObject("cited") { put("type", "integer") }
    },
    required = listOf("id", "createdAt", "summary", "sources", "cited"),
)

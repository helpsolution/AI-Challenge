package advent.pipeline.bot

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Проверка передачи данных между шагами конвейера.
 *
 * Данные от шага к шагу несёт модель: она переписывает результат одного инструмента в аргументы
 * следующего и может по дороге что-то сократить, перепутать или выдумать. Бот видит и то, что вернул
 * инструмент, и то, что модель передала дальше, — поэтому сравнивает одно с другим поле за полем
 * и пишет итог строкой трассировки. Ничего не блокирует: задача — сделать искажение видимым.
 */
class Handoff {
    /** Статьи из последнего успешного habr_search: id → статья. */
    private var found: Map<String, JsonObject>? = null

    /** Последний успешный habr_summarize: summary и sources. */
    private var summarized: JsonObject? = null

    /** Строка трассировки для шага; заодно запоминает результат для проверки следующего шага. */
    fun describe(step: Step): String {
        val outcome = step.outcome
        val arguments = step.arguments
        if (arguments == null) return "❌ ${step.tool}: аргументы модели не разобраны как JSON"
        return when (step.tool) {
            "habr_search" -> {
                val topic = arguments.string("query")?.let { " «$it»" }.orEmpty()
                if (outcome.isError) return "❌ habr_search$topic: ${outcome.text}"
                val items = outcome.structured?.array("items").orEmpty().map { it.jsonObject }
                found = items.associateBy { it.string("id").orEmpty() }
                "🔎 habr_search$topic → ${items.size} ${plural(items.size, "статья", "статьи", "статей")}"
            }

            "habr_summarize" -> {
                val check = checkItems(arguments.array("items").orEmpty())
                if (outcome.isError) return "❌ habr_summarize ← $check\n    ${outcome.text}"
                summarized = outcome.structured
                val cited = outcome.structured?.int("cited") ?: 0
                "🧠 habr_summarize ← $check\n    → сводка ссылается на $cited"
            }

            "report_save" -> {
                val check = checkSummary(arguments)
                if (outcome.isError) return "❌ report_save ← $check\n    ${outcome.text}"
                "💾 report_save ← $check\n    → отчёт #${outcome.structured?.long("id")}"
            }

            "report_latest" -> {
                if (outcome.isError) return "📂 report_latest: ${outcome.text}"
                "📂 report_latest → отчёт #${outcome.structured?.long("id")}"
            }

            else -> if (outcome.isError) "❌ ${step.tool}: ${outcome.text}" else "🔧 ${step.tool}"
        }
    }

    /** Дошли ли до summarize ровно те статьи, что вернул поиск: те же id, те же поля, ничего лишнего. */
    private fun checkItems(passed: List<JsonElement>): String {
        val source = found ?: return "${passed.size} ${articles(passed.size)} ⚠️ без поиска: их не вернул habr_search"
        val items = passed.mapNotNull { it as? JsonObject }
        val byId = items.associateBy { it.string("id").orEmpty() }

        val lost = source.keys - byId.keys
        val foreign = byId.keys - source.keys
        val changed = byId.filterKeys { it in source }.mapNotNull { (id, item) ->
            val original = source.getValue(id)
            val fields = ARTICLE_FIELDS.filter { normalize(item[it]) != normalize(original[it]) }
            if (fields.isEmpty()) return@mapNotNull null
            val first = fields.first()
            val detail = difference(original.string(first), item.string(first))?.let { " $it" }.orEmpty()
            "$id: ${fields.joinToString(", ")}$detail"
        }

        val head = "${items.size} из ${source.size} ${articles(source.size)}"
        if (lost.isEmpty() && foreign.isEmpty() && changed.isEmpty() && items.size == passed.size) {
            return "$head ✅ дошли без изменений"
        }
        return buildList {
            if (lost.isNotEmpty()) add("потеряно ${lost.size}")
            if (foreign.isNotEmpty()) add("не из поиска ${foreign.size}")
            if (changed.isNotEmpty()) add("изменено ${changed.size} (${changed.take(3).joinToString("; ")})")
            if (items.size != passed.size) add("не объекты ${passed.size - items.size}")
        }.joinToString(", ", prefix = "$head ⚠️ ")
    }

    /** Дошли ли до save текст сводки и источники из summarize без изменений. */
    private fun checkSummary(arguments: JsonObject): String {
        val source = summarized ?: return "⚠️ без сводки: её не вернул habr_summarize"
        val expected = source.string("summary").orEmpty()
        val actual = arguments.string("summary").orEmpty()
        val text = when {
            actual == expected -> "текст ✅"
            actual.squeeze() == expected.squeeze() -> "текст ✅ (с точностью до пробелов)"
            else -> "текст ⚠️ изменён: было ${expected.length} симв., пришло ${actual.length}, ${difference(expected, actual)}"
        }

        val expectedSources = source.array("sources").orEmpty().map(::normalize)
        val actualSources = arguments.array("sources").orEmpty().map(::normalize)
        val sources = when {
            actualSources == expectedSources -> "источники ✅ ${actualSources.size}"
            actualSources.toSet() == expectedSources.toSet() -> "источники ✅ ${actualSources.size} (в другом порядке)"
            else -> "источники ⚠️ ${actualSources.size} из ${expectedSources.size}, " +
                "совпало ${actualSources.toSet().intersect(expectedSources.toSet()).size}"
        }
        return "$text, $sources"
    }

    /** Отсутствующее поле и null — одно и то же: MCP не пишет в JSON пустые поля, а модель может их дописать. */
    private fun normalize(element: JsonElement?): JsonElement? = when (element) {
        null, JsonNull -> null
        is JsonObject -> JsonObject(element.filterValues { it !is JsonNull })
        else -> element
    }

    /**
     * Где строки впервые расходятся: позиция и по кусочку вокруг. Коды символов нужны потому,
     * что чаще всего модель меняет то, чего не видно, — неразрывный пробел на обычный, «ё» на «е».
     */
    private fun difference(expected: String?, actual: String?): String? {
        if (expected == null || actual == null) return null
        val at = expected.zip(actual).indexOfFirst { (a, b) -> a != b }.takeIf { it >= 0 }
            ?: minOf(expected.length, actual.length)
        fun piece(text: String) = text.substring(at, minOf(text.length, at + DIFF_CONTEXT)).ifEmpty { "∅" }
        fun code(text: String) = text.getOrNull(at)?.let { "U+%04X".format(it.code) } ?: "конец"
        return "с символа ${at + 1}: «${piece(expected)}» → «${piece(actual)}» (${code(expected)} → ${code(actual)})"
    }

    private fun String.squeeze(): String = trim().replace(WHITESPACE, " ")

    private fun articles(n: Int) = plural(n, "статья", "статьи", "статей")

    private companion object {
        val ARTICLE_FIELDS = listOf("title", "link", "author", "publishedAt", "tags", "text")
        val WHITESPACE = Regex("\\s+")
        const val DIFF_CONTEXT = 12
    }
}

internal fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.array(name: String): JsonArray? = this[name] as? JsonArray

internal fun plural(n: Int, one: String, few: String, many: String): String {
    val lastTwo = n % 100
    val last = n % 10
    return when {
        lastTwo in 11..14 -> many
        last == 1 -> one
        last in 2..4 -> few
        else -> many
    }
}

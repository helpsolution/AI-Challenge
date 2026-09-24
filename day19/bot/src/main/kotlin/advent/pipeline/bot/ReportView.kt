package advent.pipeline.bot

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Отчёт для человека. Собирается кодом из structuredContent report_save или report_latest,
 * а не из пересказа модели: то, что видит пользователь, — ровно то, что лежит в базе.
 */
object ReportView {
    fun html(report: JsonObject): String {
        val sources = sources(report)
        val body = Html.linkify(report.string("summary").orEmpty(), sources.map { it.id to it.link })
        return "<b>${Html.escape(title(report))}</b>\n<i>${Html.escape(subtitle(report))}</i>\n\n$body\n\n" +
            Html.escape(footer(report, sources.size))
    }

    /** Для режима --ask: тот же отчёт обычным текстом, ссылки списком внизу. */
    fun plain(report: JsonObject): String = buildString {
        val sources = sources(report)
        appendLine(title(report))
        appendLine(subtitle(report))
        appendLine()
        appendLine(report.string("summary").orEmpty())
        appendLine()
        sources.forEach { appendLine("[${it.id}] ${it.title} — ${it.link}") }
        append(footer(report, sources.size))
    }

    private data class Source(val id: String, val title: String, val link: String)

    private fun sources(report: JsonObject): List<Source> = report.array("sources").orEmpty().map {
        val source = it.jsonObject
        Source(source.string("id").orEmpty(), source.string("title").orEmpty(), source.string("link").orEmpty())
    }

    private fun title(report: JsonObject): String =
        "🗞 Хабр · " + (report.string("query")?.let { "«$it»" } ?: "свежая лента")

    private fun subtitle(report: JsonObject): String {
        val at = report.string("createdAt")?.let { CLOCK.format(Instant.parse(it)) } ?: "?"
        return "отчёт #${report.long("id")} · $at"
    }

    private fun footer(report: JsonObject, sources: Int): String =
        "📚 Источников: $sources · в сводке упомянуто: ${report.int("cited") ?: 0}"

    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault())
}

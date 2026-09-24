package advent.pipeline.service

import advent.pipeline.storage.ReportRepository
import advent.pipeline.web.Report
import advent.pipeline.web.SaveReportRequest
import advent.pipeline.web.Source
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit

class ReportNotFoundException(message: String) : RuntimeException(message)

/** Шаг 3 конвейера — сохранить результат; и чтение последнего сохранённого. */
@Service
class ReportService(private val reports: ReportRepository) {
    fun save(request: SaveReportRequest): Report {
        val query = Validation.query(request.query)
        val summary = request.summary?.trim()
        require(!summary.isNullOrEmpty()) { "Поле summary пустое или не передано" }
        require(summary.length <= MAX_SUMMARY_LENGTH) { "Поле summary длиннее $MAX_SUMMARY_LENGTH символов" }

        val sources = Validation.items(request.sources, "sources").mapIndexed { index, source ->
            val where = "sources[$index]"
            Source(
                id = Validation.id(source.id, where),
                title = Validation.title(source.title, where),
                link = Validation.link(source.link, where),
            )
        }
        Validation.uniqueIds(sources.map { it.id }, "sources")

        // Главная проверка шага: сводка и источники должны быть от одного и того же summarize.
        // Ссылка [id] без источника — признак того, что по дороге что-то потерялось или перепуталось.
        val cited = Validation.citations(summary)
        val orphans = cited - sources.map { it.id }.toSet()
        require(orphans.isEmpty()) {
            "Сводка ссылается на статьи, которых нет в sources: ${orphans.joinToString(", ")}. " +
                "Передайте summary и sources из одного ответа summarize без изменений."
        }

        val createdAt = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val id = reports.insert(createdAt, query, summary, sources)
        return Report(id, createdAt.toString(), query, summary, sources, cited.size)
    }

    fun latest(): Report {
        val stored = reports.latest() ?: throw ReportNotFoundException("Сохранённых отчётов пока нет")
        return Report(
            id = stored.id,
            createdAt = stored.createdAt,
            query = stored.query,
            summary = stored.summary,
            sources = stored.sources,
            cited = (Validation.citations(stored.summary) intersect stored.sources.map { it.id }.toSet()).size,
        )
    }

    private companion object {
        const val MAX_SUMMARY_LENGTH = 20_000
    }
}

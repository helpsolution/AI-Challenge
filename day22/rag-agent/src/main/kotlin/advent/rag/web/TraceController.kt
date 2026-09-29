package advent.rag.web

import advent.rag.trace.Trace
import advent.rag.trace.TraceJournal
import advent.rag.trace.TraceSummary
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Журнал запросов для страницы «Внутри агента». */
@RestController
@RequestMapping("/api/traces")
class TraceController(private val journal: TraceJournal) {

    /** Все запросы с момента запуска, новые первыми. */
    @GetMapping
    fun list(): List<TraceSummary> = journal.list()

    /** Все шаги одного запроса: векторы, рейтинг, промпт и HTTP-обмены. */
    @GetMapping("/{id}")
    fun get(@PathVariable id: String): Trace = journal.get(id)
}

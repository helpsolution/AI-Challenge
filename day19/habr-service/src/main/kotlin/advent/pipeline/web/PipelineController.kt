package advent.pipeline.web

import advent.pipeline.service.ReportService
import advent.pipeline.service.SearchService
import advent.pipeline.service.SummarizeService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/api")
class PipelineController(
    private val search: SearchService,
    private val summarizer: SummarizeService,
    private val reports: ReportService,
) {
    @GetMapping("/search")
    fun search(
        @RequestParam(required = false) query: String?,
        @RequestParam(defaultValue = "10") limit: Int,
    ): SearchResult = search.search(query, limit)

    @PostMapping("/summarize")
    fun summarize(@RequestBody request: SummarizeRequest): Summary = summarizer.summarize(request)

    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.CREATED)
    fun save(@RequestBody request: SaveReportRequest): Report = reports.save(request)

    @GetMapping("/reports/latest")
    fun latest(): Report = reports.latest()
}

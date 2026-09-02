package advent.day3.web

import advent.day3.llm.LlmRunner
import advent.day3.reasoning.ReasoningService
import advent.day3.reasoning.Technique
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class ReasoningController(
    private val reasoningService: ReasoningService,
    private val runner: LlmRunner,
) {

    /** Одна задача, решённая выбранными способами рассуждения, плюс сводка сравнения. */
    @PostMapping("/solve")
    fun solve(@Valid @RequestBody request: SolveRequest): SolveResponse = reasoningService.solve(request)

    @GetMapping("/techniques")
    fun techniques(): List<TechniqueInfo> = Technique.entries.map {
        TechniqueInfo(id = it, title = it.title, summary = it.summary, calls = it.calls)
    }

    @GetMapping("/models")
    fun models(): ModelsResponse = ModelsResponse(
        models = runner.allowedModels(),
        defaultModel = runner.defaultModel(),
    )
}

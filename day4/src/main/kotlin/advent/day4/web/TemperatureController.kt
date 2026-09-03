package advent.day4.web

import advent.day4.llm.LlmRunner
import advent.day4.temperature.TemperatureBand
import advent.day4.temperature.TemperatureService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class TemperatureController(
    private val temperatureService: TemperatureService,
    private val runner: LlmRunner,
) {

    /** Один запрос на нескольких температурах плюс сводка сравнения. */
    @PostMapping("/compare")
    fun compare(@Valid @RequestBody request: CompareRequest): CompareResponse =
        temperatureService.compare(request)

    /** Справочник диапазонов: интерфейс строит по нему таблицу «для каких задач какая температура». */
    @GetMapping("/bands")
    fun bands(): List<BandInfo> = TemperatureBand.entries.map {
        BandInfo(
            id = it,
            title = it.title,
            emoji = it.emoji,
            range = it.range,
            summary = it.summary,
            bestFor = it.bestFor,
            avoidFor = it.avoidFor,
            upperBound = it.upperBound,
        )
    }

    @GetMapping("/models")
    fun models(): ModelsResponse = ModelsResponse(
        models = runner.allowedModels(),
        defaultModel = runner.defaultModel(),
    )
}

package advent.day2.web

import advent.day2.compare.CompareService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class FormatController(private val compareService: CompareService) {

    /** Один запрос двумя прогонами: свободным и зажатым ограничениями. */
    @PostMapping("/compare")
    fun compare(@Valid @RequestBody request: CompareRequest): CompareResponse =
        compareService.compare(request)

    /** Тот же зажатый запрос N раз подряд — держится ли формат. */
    @PostMapping("/determinism")
    fun determinism(@Valid @RequestBody request: DeterminismRequest): DeterminismResponse =
        compareService.determinism(request)
}

package advent.day5.web

import advent.day5.catalog.ModelCatalog
import advent.day5.catalog.ModelTier
import advent.day5.compare.CompareService
import advent.day5.compare.TaskPresets
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class CompareController(
    private val compareService: CompareService,
    private val catalog: ModelCatalog,
) {

    /** Один запрос на трёх уровнях моделей плюс сводка сравнения. */
    @PostMapping("/compare")
    fun compare(@Valid @RequestBody request: CompareRequest): CompareResponse =
        compareService.compare(request)

    /** Каталог: уровни и модели с ценами и ссылками. */
    @GetMapping("/catalog")
    fun catalog(): CatalogResponse {
        val models = catalog.cards()
        return CatalogResponse(
            tiers = ModelTier.entries.map {
                TierInfo(
                    id = it,
                    title = it.title,
                    emoji = it.emoji,
                    summary = it.summary,
                    goodFor = it.goodFor,
                    weakAt = it.weakAt,
                )
            },
            models = models.map { it.toView() },
            // Прайс приходит от провайдера отдельным запросом. Если он не ответил,
            // интерфейс должен сказать «цены недоступны», а не показать пустые ячейки.
            pricingUnavailable = models.isNotEmpty() && models.all { it.promptPricePerMillion == null },
        )
    }

    /** Заготовленные задачи: на них разница между уровнями видна. */
    @GetMapping("/presets")
    fun presets(): List<TaskPresets.Preset> = TaskPresets.all
}

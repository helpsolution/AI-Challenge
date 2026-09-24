package advent.habr.web

import advent.habr.service.ControlService
import advent.habr.service.DigestService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Весь API — три запроса:
 *  - GET /api/status — включён ли сбор, когда был последний и когда следующий;
 *  - PUT /api/enabled {"enabled": true|false} — включить или выключить сбор;
 *  - GET /api/digest?minutes=60 или ?afterId=123 — сводка за период.
 */
@RestController
@RequestMapping("/api")
class NewsController(
    private val control: ControlService,
    private val digests: DigestService,
) {
    @GetMapping("/status")
    fun status(): Status = control.status()

    @PutMapping("/enabled")
    fun setEnabled(@RequestBody request: EnabledRequest): Status =
        control.setEnabled(requireNotNull(request.enabled) { "Поле enabled обязательно: true или false" })

    @GetMapping("/digest")
    fun digest(
        /** За сколько последних минут, по времени публикации. По умолчанию сутки. */
        @RequestParam(required = false) minutes: Int?,
        /** Вместо minutes: всё, что собрано после этого курсора (поле cursor прошлой сводки). */
        @RequestParam(required = false) afterId: Long?,
        @RequestParam(defaultValue = "100") limit: Int,
    ): Digest = digests.digest(minutes, afterId, limit)
}

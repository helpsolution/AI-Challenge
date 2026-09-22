package advent.jevagent.web

import advent.jevagent.agent.SupportAnalysisAgent
import advent.jevagent.dto.AnalysisRequest
import advent.jevagent.dto.InputError
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class AnalysisController(private val agent: SupportAnalysisAgent) {
    @GetMapping("/status")
    fun status() = agent.status()

    @PostMapping("/analyze")
    fun analyze(@RequestBody request: AnalysisRequest): ResponseEntity<Any> {
        val message = request.text
        if (message.isBlank()) return ResponseEntity.badRequest().body(InputError("Введите текст обращения."))
        if (message.length > 5000) return ResponseEntity.badRequest().body(InputError("Максимальная длина обращения — 5000 символов."))

        val result = agent.analyze(message)
        val status = when {
            result.error == null -> HttpStatus.OK
            result.upstreamStatus == null -> HttpStatus.SERVICE_UNAVAILABLE
            else -> HttpStatus.BAD_GATEWAY
        }
        return ResponseEntity.status(status).body(result)
    }
}

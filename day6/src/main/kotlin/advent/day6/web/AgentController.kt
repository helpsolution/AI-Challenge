package advent.day6.web

import advent.day6.agent.Agent
import advent.day6.agent.AgentSnapshot
import advent.day6.agent.PersonaPreset
import advent.day6.agent.PersonaPresets
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Тонкий слой между браузером и агентом. Ничего не решает сам: снимок и настройки отдаёт
 * агент, а ход — это его протокол, отданный как есть.
 */
@RestController
@RequestMapping("/api")
class AgentController(private val agent: Agent) {

    @GetMapping("/agent")
    fun snapshot(): AgentSnapshot = agent.snapshot()

    @PutMapping("/agent/settings")
    fun reconfigure(@RequestBody request: SettingsRequest): AgentSnapshot = agent.reconfigure(request.toSettings())

    @GetMapping("/presets")
    fun presets(): List<PersonaPreset> = PersonaPresets.all

    /**
     * Один ход агента: вопрос внутрь, протокол наружу.
     *
     * Сбой модели приходит телом того же вида, но со статусом 502: ход состоялся и попал
     * в журнал, просто закончился неудачей — интерфейсу нужны его шаги, чтобы показать,
     * на чём всё оборвалось.
     */
    @PostMapping("/agent/chat")
    fun chat(@RequestBody request: ChatRequest): ResponseEntity<TurnOutcome> {
        val turn = agent.ask(request.text)
        val outcome = TurnOutcome(turn, agent.snapshot())
        val status = if (turn.failed) HttpStatus.BAD_GATEWAY else HttpStatus.OK
        return ResponseEntity.status(status).body(outcome)
    }
}

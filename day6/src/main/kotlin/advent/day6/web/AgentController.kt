package advent.day6.web

import advent.day6.agent.Agent
import advent.day6.agent.AgentBusyException
import advent.day6.agent.AgentEvent
import advent.day6.agent.AgentSnapshot
import advent.day6.agent.PersonaPreset
import advent.day6.agent.PersonaPresets
import advent.day6.config.DeepSeekProperties
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException

/**
 * Тонкий слой между браузером и агентом. Ничего не решает сам: снимок, настройки и память
 * отдаёт агент, чат — это его события, переложенные в server-sent events.
 */
@RestController
@RequestMapping("/api")
class AgentController(
    private val agent: Agent,
    private val deepSeek: DeepSeekProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/agent")
    fun snapshot(): AgentSnapshot = agent.snapshot()

    @PutMapping("/agent/settings")
    fun reconfigure(@RequestBody request: SettingsRequest): AgentSnapshot = agent.reconfigure(request.toSettings())

    @DeleteMapping("/agent/memory")
    fun forget(): AgentSnapshot = agent.forget()

    @GetMapping("/presets")
    fun presets(): List<PersonaPreset> = PersonaPresets.all

    /**
     * Ход агента как поток событий. Ответ начинает уходить в браузер с первым токеном,
     * а не после того, как модель договорит.
     */
    @PostMapping("/agent/chat", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun chat(@RequestBody request: ChatRequest): SseEmitter {
        val text = request.text.trim()
        require(text.isNotEmpty()) { "Введите сообщение" }
        if (agent.isBusy) throw AgentBusyException(agent.settings.name)

        val emitter = SseEmitter(deepSeek.readTimeout.toMillis() + EMITTER_GRACE_MS)
        Thread.startVirtualThread {
            val sink = EventSink(emitter)
            try {
                agent.ask(text, sink::accept)
                emitter.complete()
            } catch (e: Exception) {
                log.warn("Ход прерван до ответа: {}", e.message)
                emitter.completeWithError(e)
            }
        }
        return emitter
    }

    /**
     * Переводит события агента в SSE. Если браузер закрыл вкладку посреди ответа, поток просто
     * замолкает — но агента это не прерывает: ход обязан дойти до памяти, иначе после
     * перезагрузки страницы диалог окажется с дырой.
     */
    private inner class EventSink(private val emitter: SseEmitter) {
        private var clientGone = false

        fun accept(event: AgentEvent) {
            if (clientGone) return
            try {
                emitter.send(event.toSse())
            } catch (e: IOException) {
                clientGone = true
                log.info("Клиент отключился, ход довожу до конца без него")
            } catch (e: IllegalStateException) {
                clientGone = true
            }
        }

        private fun AgentEvent.toSse(): SseEmitter.SseEventBuilder = when (this) {
            is AgentEvent.StateChanged -> sse("state", StateView(state, mood))
            is AgentEvent.Step -> sse("step", step)
            is AgentEvent.Token -> sse("token", TextChunk(text))
            is AgentEvent.Reasoning -> sse("reasoning", TextChunk(text))
            is AgentEvent.Completed -> sse("done", TurnOutcome(turn, agent.snapshot()))
            is AgentEvent.Failed -> sse("error", TurnOutcome(turn, agent.snapshot()))
        }

        private fun sse(name: String, payload: Any): SseEmitter.SseEventBuilder =
            SseEmitter.event().name(name).data(payload, MediaType.APPLICATION_JSON)
    }

    private companion object {
        /** Запас поверх таймаута провайдера: эмиттер не должен закрыться раньше, чем сдастся клиент к LLM. */
        const val EMITTER_GRACE_MS = 30_000L
    }
}

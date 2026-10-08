package advent.rag.web

import advent.rag.agent.Agent
import advent.rag.agent.AgentListener
import advent.rag.agent.ModelAnswer
import advent.rag.agent.ModelRef
import advent.rag.agent.RetrievalResult
import advent.rag.llm.ChatMessage
import advent.rag.llm.ModelStatus
import advent.rag.llm.Models
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
import tools.jackson.databind.json.JsonMapper

data class AskRequest(val question: String, val history: List<ChatMessage> = emptyList())

private val NDJSON = MediaType.parseMediaType("application/x-ndjson")
private val TEXT_UTF8 = MediaType("text", "plain", Charsets.UTF_8)

/**
 * Ответ `/api/ask` — NDJSON, событие на строку: `retrieval` (поиск и промпт, общие для всех моделей),
 * `started` и `answer` по каждой модели по мере готовности, в конце `done` или `error` с текстом ошибки как есть.
 */
@RestController
@RequestMapping("/api")
class AgentController(private val agent: Agent, private val models: Models, private val mapper: JsonMapper) {

    // ResponseBodyEmitter, а не StreamingResponseBody: в Spring 7 тот буферизует весь ответ до конца.
    @PostMapping("/ask")
    fun ask(@RequestBody request: AskRequest): ResponseEntity<ResponseBodyEmitter> {
        // Ошибки ввода — обычным 400 до начала стрима.
        agent.validate(request.question, request.history)
        // Три модели подряд на ноутбуке легко идут дольше 30 секунд — таймаута сервлет-контейнера по умолчанию.
        val emitter = ResponseBodyEmitter(STREAM_TIMEOUT_MS)
        Thread.startVirtualThread {
            val events = Events(emitter)
            try {
                agent.ask(request.question, request.history, events)
                events.send("done", null)
                emitter.complete()
            } catch (e: Exception) {
                runCatching { events.send("error", mapOf("message" to (e.message ?: e.javaClass.simpleName))); emitter.complete() }
                    .onFailure { emitter.completeWithError(e) }
            }
        }
        return ResponseEntity.ok().contentType(NDJSON).body(emitter)
    }

    @GetMapping("/models")
    fun models(): List<ModelStatus> = models.status()

    private inner class Events(private val emitter: ResponseBodyEmitter) : AgentListener {
        override fun retrieved(result: RetrievalResult) = send("retrieval", result)
        override fun started(model: ModelRef) = send("started", model)
        override fun answered(answer: ModelAnswer) = send("answer", answer)

        // Облачная модель шлёт события из своего потока — запись в ответ должна идти по одной строке.
        @Synchronized
        fun send(type: String, data: Any?) = emitter.send(mapper.writeValueAsString(mapOf("type" to type, "data" to data)) + "\n", TEXT_UTF8)
    }

    private companion object {
        const val STREAM_TIMEOUT_MS = 15 * 60 * 1000L
    }
}

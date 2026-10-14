package advent.support.web

import advent.support.agent.CrmTool
import advent.support.agent.PendingActions
import advent.support.agent.SupportAgent
import advent.support.agent.SupportRequest
import advent.support.crm.CrmClient
import advent.support.crm.CrmUnavailable
import advent.support.knowledge.Indexer
import advent.support.knowledge.KnowledgeBase
import advent.support.llm.DeepSeekClient
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

data class ActionRequest(val customerId: String)

@RestController
class SupportController(private val agent: SupportAgent, private val crm: CrmClient, private val pending: PendingActions,
    private val knowledge: KnowledgeBase, private val indexer: Indexer, private val llm: DeepSeekClient) {

    @GetMapping("/api/status") fun status() = mapOf(
        "model" to llm.model, "llmConfigured" to llm.configured, "mcpUrl" to crm.url,
        "index" to knowledge.meta, "indexError" to indexer.lastError)

    @GetMapping("/api/customers") fun customers() = crm.session { it.call("list_customers", emptyMap()) }.orFail().path("customers")

    @GetMapping("/api/customers/{id}/tickets") fun tickets(@PathVariable id: String) =
        crm.session { it.call("list_tickets", mapOf(CrmTool.CUSTOMER_ID to id, "status" to "all")) }.orFail().path("tickets")

    @PostMapping("/api/chat", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun chat(@RequestBody request: SupportRequest): SseEmitter {
        agent.validate(request)
        val emitter = SseEmitter(300_000L)
        Thread.startVirtualThread {
            try {
                agent.answer(request) { event, data -> emitter.send(SseEmitter.event().name(event).data(data)) }
                emitter.complete()
            } catch (e: Exception) { emitter.completeWithError(e) }
        }
        return emitter
    }

    // Запись в CRM выполняется только здесь — по нажатию клиента и только для действия, которое предложила модель.
    @PostMapping("/api/actions/{id}/confirm") fun confirm(@PathVariable id: String, @RequestBody request: ActionRequest): Any {
        val action = pending.take(id, request.customerId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Действие уже выполнено или не найдено")
        return crm.session { it.call(action.tool, action.arguments + (CrmTool.CUSTOMER_ID to action.customerId)) }.orFail()
    }

    @PostMapping("/api/actions/{id}/cancel") fun cancel(@PathVariable id: String, @RequestBody request: ActionRequest) =
        mapOf("cancelled" to (pending.take(id, request.customerId) != null))

    @PostMapping("/api/knowledge/rebuild") fun rebuild() = indexer.ensureFresh(force = true)

    private fun advent.support.crm.CrmCall.orFail() =
        data ?: throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "CRM: $error")
}

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ResponseStatusException::class)
    fun status(e: ResponseStatusException) = ResponseEntity.status(e.statusCode).body(mapOf("message" to (e.reason ?: "Ошибка запроса")))

    @ExceptionHandler(CrmUnavailable::class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    fun crm(e: CrmUnavailable) = mapOf("message" to e.message)

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun badRequest(e: IllegalArgumentException) = mapOf("message" to (e.message ?: "Некорректный запрос"))

    @ExceptionHandler(Exception::class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    fun failure(e: Exception) = mapOf("message" to (e.message ?: "Ошибка сервиса"))
}

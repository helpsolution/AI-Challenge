package advent.rag.web

import advent.rag.agent.*
import advent.rag.knowledge.*
import advent.rag.llm.CloudLlmProperties
import advent.rag.project.RepoDocuments
import advent.rag.trace.TraceJournal
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.nio.file.Files

@RestController
class AssistantController(private val assistant: DeveloperAssistant, private val documents: RepoDocuments,
    private val knowledge: KnowledgeBase, private val indexer: Indexer, private val journal: TraceJournal,
    private val llm: CloudLlmProperties) {
    @GetMapping("/api/project") fun project() = mapOf(
        "name" to documents.root.fileName.toString(), "scopes" to documents.scopes(),
        "model" to llm.model, "llmConfigured" to llm.apiKey.isNotBlank(), "index" to knowledge.meta,
        "indexError" to indexer.lastError, "commands" to listOf("/help"))

    @PostMapping("/api/help") fun help(@RequestBody request: HelpRequest) = assistant.ask(request)

    @PostMapping("/api/help/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@RequestBody request: HelpRequest): SseEmitter {
        assistant.validate(request)
        val emitter = SseEmitter(240_000L)
        Thread.startVirtualThread {
            try {
                emitter.send(SseEmitter.event().name("start").data(mapOf("message" to request.message)))
                val answer = assistant.ask(request) { step ->
                    emitter.send(SseEmitter.event().name("step").data(step))
                }
                emitter.send(SseEmitter.event().name("answer").data(answer))
                emitter.complete()
            } catch (e: Exception) { emitter.completeWithError(e) }
        }
        return emitter
    }

    @PostMapping("/api/knowledge/rebuild") fun rebuild() = indexer.ensureFresh(force = true)
    @GetMapping("/api/traces") fun traces() = journal.recent()
    @GetMapping("/api/traces/{id}") fun trace(@PathVariable id: String) =
        journal.get(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Нет трассировки")
    @GetMapping("/api/source", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun source(@RequestParam path: String): String {
        require(knowledge.chunks().any { it.source == path }) { "Путь не относится к документации индекса" }
        val file = documents.root.resolve(path).toRealPath()
        require(file.startsWith(documents.root)) { "Путь вне проекта" }
        return Files.readString(file)
    }
}

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ResponseStatusException::class)
    fun status(e: ResponseStatusException) = org.springframework.http.ResponseEntity.status(e.statusCode)
        .body(mapOf("message" to (e.reason ?: "Ошибка запроса")))
    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun badRequest(e: IllegalArgumentException) = mapOf("message" to (e.message ?: "Некорректный запрос"))
    @ExceptionHandler(Exception::class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    fun failure(e: Exception) = mapOf("message" to (e.message ?: "Ошибка сервиса"))
}

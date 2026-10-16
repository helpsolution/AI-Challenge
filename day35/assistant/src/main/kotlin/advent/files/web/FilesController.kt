package advent.files.web

import advent.files.agent.FileAgent
import advent.files.agent.TaskRequest
import advent.files.llm.DeepSeekClient
import advent.files.mcp.FilesClient
import advent.files.mcp.FilesUnavailable
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
class FilesController(private val agent: FileAgent, private val mcp: FilesClient, private val llm: DeepSeekClient) {
    @GetMapping("/api/status") fun status() = mapOf("model" to llm.model, "llmConfigured" to llm.configured, "mcpUrl" to mcp.url)

    @GetMapping("/api/project") fun project() = mcp.session {
        val call = it.call("get_project", emptyMap())
        call.data ?: throw ResponseStatusException(HttpStatus.BAD_GATEWAY, call.error)
    }

    @PostMapping("/api/tasks", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun task(@RequestBody request: TaskRequest): SseEmitter {
        agent.validate(request)
        val emitter = SseEmitter(600_000L)
        Thread.startVirtualThread {
            try {
                agent.answer(request) { event, data -> emitter.send(SseEmitter.event().name(event).data(data)) }
                emitter.complete()
            } catch (e: Exception) { emitter.completeWithError(e) }
        }
        return emitter
    }

    @GetMapping("/api/runs/{id}/changes") fun changes(@PathVariable id: String) = mcp.session {
        val call = it.call("get_changes", mapOf("workspace_id" to id))
        call.data ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, call.error)
    }

    @GetMapping("/api/runs/{id}/diff", produces = [MediaType.TEXT_PLAIN_VALUE]) fun diff(@PathVariable id: String): String =
        changes(id).path("changes").values().joinToString("") { it.path("diff").asString() }
}

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ResponseStatusException::class)
    fun status(e: ResponseStatusException) = ResponseEntity.status(e.statusCode).body(mapOf("message" to e.reason))
    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun bad(e: IllegalArgumentException) = mapOf("message" to e.message)
    @ExceptionHandler(FilesUnavailable::class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    fun unavailable(e: FilesUnavailable) = mapOf("message" to e.message)
    @ExceptionHandler(Exception::class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    fun failure(e: Exception) = mapOf("message" to e.message)
}

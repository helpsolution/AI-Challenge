package advent.llmservice.api

import advent.llmservice.limits.CLIENT
import advent.llmservice.ollama.ContextOverflowException
import advent.llmservice.ollama.OllamaException
import advent.llmservice.status.RequestJournal
import advent.llmservice.status.RequestRecord
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant
import kotlin.time.TimeMark

/** Все отказы — в формате ошибок OpenAI. Отказы чата ещё и записываются в журнал: их видно в панели сервиса. */
@RestControllerAdvice
class ApiErrors(private val journal: RequestJournal) {

    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException, request: HttpServletRequest) = respond(e, request)

    @ExceptionHandler(ContextOverflowException::class)
    fun contextOverflow(e: ContextOverflowException, request: HttpServletRequest) = respond(
        ApiException.contextExceeded(
            "Окно модели — ${e.contextTokens} токенов, а запрос занимает ${e.promptTokens}. " +
                "Сократите историю диалога или текст",
        ),
        request,
    )

    // Ошибку Ollama отдаём дословно: по ней видно, что именно случилось с моделью.
    @ExceptionHandler(OllamaException::class)
    fun upstream(e: OllamaException, request: HttpServletRequest) =
        respond(ApiException.modelUnavailable(e.message.orEmpty()), request)

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(e: HttpMessageNotReadableException, request: HttpServletRequest) = respond(
        ApiException.invalidRequest("Тело запроса не разобрано: ${e.mostSpecificCause.message?.take(200)}"),
        request,
    )

    private fun respond(e: ApiException, request: HttpServletRequest): ResponseEntity<ErrorBody> {
        if (request.requestURI == "/v1/chat/completions") {
            journal.record(
                RequestRecord(
                    at = Instant.now(),
                    client = request.getAttribute(CLIENT) as String? ?: "без ключа",
                    status = e.status.value(),
                    code = e.code,
                    stream = request.getAttribute(STREAM) as Boolean?,
                    totalMs = (request.getAttribute(RECEIVED) as TimeMark?)?.elapsedNow()?.inWholeMilliseconds ?: 0,
                    queueMs = request.getAttribute(QUEUE_MS) as Long?,
                ),
            )
        }
        return ResponseEntity.status(e.status)
            .headers { headers -> e.headers.forEach(headers::set) }
            .body(ErrorBody(ErrorDetail(e.message.orEmpty(), e.type, e.code)))
    }
}

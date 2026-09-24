package advent.pipeline.web

import advent.pipeline.feed.FeedException
import advent.pipeline.llm.LlmException
import advent.pipeline.service.ReportNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/**
 * Ошибки отдаются текстом, который поймёт человек: его же MCP-сервер покажет модели,
 * и по нему она решает, что исправить в аргументах.
 */
@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(e: IllegalArgumentException) = respond(HttpStatus.BAD_REQUEST, e.message ?: "Некорректный запрос")

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadableBody(e: HttpMessageNotReadableException) =
        respond(HttpStatus.BAD_REQUEST, "Тело запроса не разобрано как JSON нужной формы")

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun wrongParameterType(e: MethodArgumentTypeMismatchException) =
        respond(HttpStatus.BAD_REQUEST, "Параметр ${e.name} имеет недопустимое значение: ${e.value}")

    @ExceptionHandler(ReportNotFoundException::class)
    fun notFound(e: ReportNotFoundException) = respond(HttpStatus.NOT_FOUND, e.message.orEmpty())

    /** Хабр или DeepSeek не ответили: виноват не запрос, а внешний сервис. */
    @ExceptionHandler(FeedException::class, LlmException::class)
    fun upstream(e: RuntimeException) = respond(HttpStatus.BAD_GATEWAY, e.message ?: "Внешний сервис не ответил")

    private fun respond(status: HttpStatus, message: String): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(status).body(ErrorResponse(message))
}

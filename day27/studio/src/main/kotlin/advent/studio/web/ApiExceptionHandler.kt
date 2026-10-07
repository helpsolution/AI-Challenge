package advent.studio.web

import advent.localllm.OllamaException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

data class ErrorResponse(val message: String)

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(e: IllegalArgumentException) = respond(HttpStatus.BAD_REQUEST, e.message ?: "Некорректный запрос")

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadableBody(e: HttpMessageNotReadableException) =
        respond(HttpStatus.BAD_REQUEST, """Ожидается JSON {"tool": "translate", "option": "auto", "text": "…"}""")

    @ExceptionHandler(OllamaException::class)
    fun upstream(e: OllamaException) = respond(HttpStatus.BAD_GATEWAY, e.message ?: "Ollama не ответила")

    private fun respond(status: HttpStatus, message: String): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(status).body(ErrorResponse(message))
}

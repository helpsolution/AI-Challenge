package advent.rag.web

import advent.rag.embedding.EmbeddingException
import advent.rag.llm.LlmException
import advent.rag.reranking.RerankerException
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
        respond(HttpStatus.BAD_REQUEST, """Ожидается JSON {"message": "…"}""")

    @ExceptionHandler(NoSuchElementException::class)
    fun notFound(e: NoSuchElementException) = respond(HttpStatus.NOT_FOUND, e.message ?: "Не найдено")

    @ExceptionHandler(IllegalStateException::class)
    fun notReady(e: IllegalStateException) = respond(HttpStatus.SERVICE_UNAVAILABLE, e.message ?: "Сервис не готов")

    @ExceptionHandler(EmbeddingException::class, LlmException::class, RerankerException::class)
    fun upstream(e: RuntimeException) = respond(HttpStatus.BAD_GATEWAY, e.message ?: "Внешний сервис не ответил")

    private fun respond(status: HttpStatus, message: String): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(status).body(ErrorResponse(message))
}

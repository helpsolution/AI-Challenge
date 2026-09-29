package advent.rag.web

import advent.rag.embedding.EmbeddingException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

data class ErrorResponse(val message: String)

/** Ошибки отдаются текстом, по которому человек в Swagger поймёт, что исправить. */
@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(e: IllegalArgumentException) = respond(HttpStatus.BAD_REQUEST, e.message ?: "Некорректный запрос")

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadableBody(e: HttpMessageNotReadableException) =
        respond(HttpStatus.BAD_REQUEST, "Тело запроса пустое или не той формы — пример есть в Swagger")

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun wrongContentType(e: HttpMediaTypeNotSupportedException) =
        respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Ожидается Content-Type: ${e.supportedMediaTypes.joinToString()}")

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun wrongParameterType(e: MethodArgumentTypeMismatchException) =
        respond(HttpStatus.BAD_REQUEST, "Параметр ${e.name} имеет недопустимое значение: ${e.value}")

    /** Ollama не ответила или ответила странно: виноват не запрос, а внешний сервис. */
    @ExceptionHandler(EmbeddingException::class)
    fun upstream(e: EmbeddingException) = respond(HttpStatus.BAD_GATEWAY, e.message ?: "Ollama не ответила")

    private fun respond(status: HttpStatus, message: String): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(status).body(ErrorResponse(message))
}

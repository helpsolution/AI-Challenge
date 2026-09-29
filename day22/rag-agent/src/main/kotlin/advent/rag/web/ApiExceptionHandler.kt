package advent.rag.web

import advent.rag.embedding.EmbeddingException
import advent.rag.llm.LlmException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

data class ErrorResponse(val message: String)

/** Ошибки уходят на страницу текстом, по которому понятно, что чинить. */
@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(e: IllegalArgumentException) = respond(HttpStatus.BAD_REQUEST, e.message ?: "Некорректный запрос")

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadableBody(e: HttpMessageNotReadableException) =
        respond(HttpStatus.BAD_REQUEST, """Ожидается JSON {"question": "…", "rag": true}""")

    /** Запроса нет в журнале трассировок. */
    @ExceptionHandler(NoSuchElementException::class)
    fun notFound(e: NoSuchElementException) = respond(HttpStatus.NOT_FOUND, e.message ?: "Не найдено")

    /** База знаний ещё не собрана. */
    @ExceptionHandler(IllegalStateException::class)
    fun notReady(e: IllegalStateException) = respond(HttpStatus.SERVICE_UNAVAILABLE, e.message ?: "Сервис не готов")

    /** Ollama или DeepSeek не ответили: виноват не запрос, а внешний сервис. */
    @ExceptionHandler(EmbeddingException::class, LlmException::class)
    fun upstream(e: RuntimeException) = respond(HttpStatus.BAD_GATEWAY, e.message ?: "Внешний сервис не ответил")

    private fun respond(status: HttpStatus, message: String): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(status).body(ErrorResponse(message))
}

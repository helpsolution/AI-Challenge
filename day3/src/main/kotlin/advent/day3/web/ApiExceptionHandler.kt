package advent.day3.web

import advent.day3.llm.LlmException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ApiExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun onValidationError(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val details = e.bindingResult.fieldErrors.map { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity.badRequest().body(ErrorResponse("Некорректные параметры запроса", details))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun onIllegalArgument(e: IllegalArgumentException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(ErrorResponse(e.message ?: "Некорректный запрос"))

    @ExceptionHandler(LlmException::class)
    fun onLlmError(e: LlmException): ResponseEntity<ErrorResponse> {
        log.warn("Ошибка LLM: {}", e.message)
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
            ErrorResponse(
                error = e.message ?: "Ошибка LLM",
                exchange = e.exchange?.toView(),
            ),
        )
    }

    @ExceptionHandler(Exception::class)
    fun onUnexpected(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("Непредвиденная ошибка", e)
        return ResponseEntity.internalServerError().body(ErrorResponse("Внутренняя ошибка сервера"))
    }
}

package advent.day7.web

import advent.day7.llm.LlmException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class ApiExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(IllegalArgumentException::class)
    fun onIllegalArgument(e: IllegalArgumentException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(ErrorResponse(e.message ?: "Некорректный запрос"))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun onUnreadable(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(ErrorResponse("Не удалось прочитать тело запроса"))

    /** Ненайденный файл — это 404, а не поломка сервера. */
    @ExceptionHandler(NoResourceFoundException::class)
    fun onMissingResource(e: NoResourceFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse("Файл не найден"))

    /**
     * Модель не ответила. В базе при этом не изменилось ничего: агент пишет пару
     * «вопрос-ответ» только после успешного ответа, так что история осталась целой.
     */
    @ExceptionHandler(LlmException::class)
    fun onLlmError(e: LlmException): ResponseEntity<ErrorResponse> {
        log.warn("Ошибка провайдера: {}", e.message)
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(ErrorResponse(e.message ?: "Ошибка обращения к модели"))
    }

    @ExceptionHandler(Exception::class)
    fun onUnexpected(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("Непредвиденная ошибка", e)
        return ResponseEntity.internalServerError().body(ErrorResponse("Внутренняя ошибка сервера"))
    }
}

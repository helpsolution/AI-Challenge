package advent.day10.web

import advent.day10.llm.LlmException
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
     * Модель не ответила. Переписка при этом не изменилась: агент пишет пару
     * «вопрос-ответ» только после успешного ответа, так что история осталась целой.
     * Сам ход, однако, уже записан в таблицу расхода — с текстом ошибки и без токенов.
     *
     * Наружу уходит и код провайдера, и его тело ответа целиком. Своё `502` мы отдаём
     * потому, что сломалось не у нас, а выше; но чей именно это был отказ и что
     * в нём написано, интерфейс должен показать дословно — иначе разбираться,
     * почему оборвался прогон, придётся по логам.
     */
    @ExceptionHandler(LlmException::class)
    fun onLlmError(e: LlmException): ResponseEntity<ErrorResponse> {
        log.warn("Ошибка провайдера {}: {}", e.providerStatus, e.message)
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
            ErrorResponse(
                error = e.message ?: "Ошибка обращения к модели",
                providerStatus = e.providerStatus,
                providerBody = e.providerBody?.take(MAX_BODY_CHARS),
            ),
        )
    }

    @ExceptionHandler(Exception::class)
    fun onUnexpected(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("Непредвиденная ошибка", e)
        return ResponseEntity.internalServerError().body(ErrorResponse("Внутренняя ошибка сервера"))
    }

    private companion object {
        /** Тело ошибки бывает длинным; для интерфейса достаточно начала. */
        const val MAX_BODY_CHARS = 4000
    }
}

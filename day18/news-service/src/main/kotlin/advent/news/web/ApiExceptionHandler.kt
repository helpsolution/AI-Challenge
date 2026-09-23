package advent.news.web

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(e: IllegalArgumentException): ResponseEntity<ErrorResponse> =
        respond(HttpStatus.BAD_REQUEST, e.message ?: "Некорректный запрос")

    @ExceptionHandler(NoSuchElementException::class)
    fun notFound(e: NoSuchElementException): ResponseEntity<ErrorResponse> =
        respond(HttpStatus.NOT_FOUND, e.message ?: "Не найдено")

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadableBody(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> =
        respond(HttpStatus.BAD_REQUEST, "Тело запроса не разобрано: ожидается JSON, поля описаны в Swagger")

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun missingParameter(e: MissingServletRequestParameterException): ResponseEntity<ErrorResponse> =
        respond(HttpStatus.BAD_REQUEST, "Не задан обязательный параметр ${e.parameterName}")

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun wrongParameterType(e: MethodArgumentTypeMismatchException): ResponseEntity<ErrorResponse> =
        respond(HttpStatus.BAD_REQUEST, "Параметр ${e.name} имеет недопустимое значение: ${e.value}")

    private fun respond(status: HttpStatus, message: String): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(status).body(ErrorResponse(message))
}

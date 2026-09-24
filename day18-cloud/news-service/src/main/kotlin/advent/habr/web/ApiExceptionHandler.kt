package advent.habr.web

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(e: IllegalArgumentException): ResponseEntity<ErrorResponse> =
        respond(e.message ?: "Некорректный запрос")

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadableBody(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> =
        respond("Тело запроса не разобрано: ожидается JSON вида {\"enabled\": true}")

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun wrongParameterType(e: MethodArgumentTypeMismatchException): ResponseEntity<ErrorResponse> =
        respond("Параметр ${e.name} имеет недопустимое значение: ${e.value}")

    private fun respond(message: String): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(message))
}

package advent.day16.weather.web

import advent.day16.weather.openmeteo.UpstreamException
import advent.day16.weather.service.CityNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(e: IllegalArgumentException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(e.message ?: "Некорректный запрос"))

    @ExceptionHandler(CityNotFoundException::class)
    fun notFound(e: CityNotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(e.message ?: "Город не найден"))

    @ExceptionHandler(UpstreamException::class)
    fun upstream(e: UpstreamException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse(e.message ?: "Источник данных недоступен"))
}

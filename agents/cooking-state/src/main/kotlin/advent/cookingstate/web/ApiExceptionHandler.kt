package advent.cookingstate.web

import advent.cookingstate.agent.SessionConflictException
import advent.cookingstate.agent.SessionNotFoundException
import advent.cookingstate.llm.LlmException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

data class ErrorResponse(val error: String)

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException::class)
    fun invalidInput(e: IllegalArgumentException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(ErrorResponse(e.message ?: "Некорректное сообщение"))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun invalidJson(): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(ErrorResponse("Ожидается JSON с полем text"))

    @ExceptionHandler(SessionNotFoundException::class)
    fun notFound(e: SessionNotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(e.message ?: "Сессия не найдена"))

    @ExceptionHandler(SessionConflictException::class)
    fun conflict(e: SessionConflictException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(e.message ?: "Конфликт изменений"))

    @ExceptionHandler(LlmException::class)
    fun llmError(e: LlmException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse(e.message ?: "Ошибка модели"))
}

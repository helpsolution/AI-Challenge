package advent.foodphoto.web

import advent.foodphoto.llm.LlmException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.support.MissingServletRequestPartException

data class ErrorResponse(val error: String)

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException::class)
    fun invalidInput(e: IllegalArgumentException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(ErrorResponse(e.message ?: "Некорректное изображение"))

    @ExceptionHandler(MissingServletRequestPartException::class)
    fun missingImage(): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(ErrorResponse("Ожидается файл в поле image"))

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun oversizedImage(): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ErrorResponse("Изображение слишком большое (максимум 5 МБ)"))

    @ExceptionHandler(LlmException::class)
    fun llmError(e: LlmException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse(e.message ?: "Ошибка OpenRouter"))
}

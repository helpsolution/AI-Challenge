package advent.llmservice.api

import org.springframework.http.HttpStatus

/**
 * Ошибка в формате OpenAI: `{"error": {"message", "type", "code"}}`. Клиенты OpenAI-SDK разбирают её сами,
 * а по `code` отличают переполнение контекста от лимита и очереди.
 */
class ApiException(
    val status: HttpStatus,
    val type: String,
    val code: String,
    message: String,
    val headers: Map<String, String> = emptyMap(),
) : RuntimeException(message) {

    companion object {
        fun invalidRequest(message: String) =
            ApiException(HttpStatus.BAD_REQUEST, "invalid_request_error", "invalid_request", message)

        fun contextExceeded(message: String) =
            ApiException(HttpStatus.BAD_REQUEST, "invalid_request_error", "context_length_exceeded", message)

        fun unauthorized(message: String) =
            ApiException(HttpStatus.UNAUTHORIZED, "invalid_request_error", "invalid_api_key", message)

        fun rateLimited(message: String, headers: Map<String, String>) =
            ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limit_error", "rate_limit_exceeded", message, headers)

        fun queueFull(message: String) =
            ApiException(HttpStatus.SERVICE_UNAVAILABLE, "server_error", "queue_full", message, mapOf("Retry-After" to "10"))

        fun modelUnavailable(message: String) =
            ApiException(HttpStatus.BAD_GATEWAY, "server_error", "model_unavailable", message)
    }
}

package advent.day6.llm

import org.springframework.http.HttpStatusCode

/** Ошибка обращения к LLM-провайдеру: сеть, таймаут, ответ с кодом не 2xx или пустой результат. */
class LlmException(
    message: String,
    val providerStatus: HttpStatusCode? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

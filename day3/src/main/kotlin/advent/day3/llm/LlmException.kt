package advent.day3.llm

import org.springframework.http.HttpStatusCode

/** Ошибка обращения к LLM-провайдеру: сеть, таймаут, ответ с кодом не 2xx или пустой результат. */
class LlmException(
    message: String,
    val providerStatus: HttpStatusCode? = null,
    /** Обмен, на котором всё сломалось, — его тоже показываем пользователю. */
    val exchange: LlmExchange? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

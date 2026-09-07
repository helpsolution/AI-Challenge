package advent.day6.llm

/** Ошибка обращения к LLM-провайдеру: сеть, таймаут, ответ с кодом не 2xx или пустой результат. */
class LlmException(
    message: String,
    /** Код ответа провайдера, если ответ был. */
    val providerStatus: Int? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

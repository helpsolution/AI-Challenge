package advent.translator.llm

data class LlmMessage(val role: String, val content: String)

/** Внешний контракт модели: сообщения на вход, текст ответа на выход. */
fun interface LlmClient {
    fun complete(messages: List<LlmMessage>): String
}

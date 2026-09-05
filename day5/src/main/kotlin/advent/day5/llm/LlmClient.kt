package advent.day5.llm

/** Контракт обращения к LLM. Провайдера можно заменить, не трогая прикладной код. */
interface LlmClient {
    fun complete(request: ChatCompletionRequest): LlmExchange
}

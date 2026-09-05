package advent.day5

import advent.day5.llm.ApiMessage
import advent.day5.llm.ChatCompletionRequest
import advent.day5.llm.ChatCompletionResponse
import advent.day5.llm.Choice
import advent.day5.llm.CompletionTokensDetails
import advent.day5.llm.LlmClient
import advent.day5.llm.LlmException
import advent.day5.llm.LlmExchange
import advent.day5.llm.Usage
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Заглушка провайдера, которая ведёт себя как три модели разного класса: слабая отвечает
 * быстро, коротко и неверно, сильная — медленно, длинно и верно. Ради этой разницы
 * задание и делается, поэтому стаб обязан её воспроизводить, а не отдавать три одинаковых ответа.
 */
class ScriptedLlmClient : LlmClient {

    val requests = CopyOnWriteArrayList<ChatCompletionRequest>()

    /** Модель, на которой провайдер отказывает. null — не отказывает. */
    @Volatile var failOn: String? = null

    @Volatile var failEverything = false

    /** Не присылать `cost`: проверяем, что стоимость тогда считается по прайсу. */
    @Volatile var omitCost = false

    fun reset() {
        requests.clear()
        failOn = null
        failEverything = false
        omitCost = false
    }

    override fun complete(request: ChatCompletionRequest): LlmExchange {
        requests += request

        if (failEverything) throw LlmException("Недостаточно средств на балансе OpenRouter (402). Пополните счёт.")
        if (failOn == request.model) throw LlmException("Площадка под моделью недоступна (502).")

        val profile = PROFILES[request.model] ?: error("стаб не знает модель ${request.model}")

        // Задержка нужна, чтобы «быстрее» и «медленнее» были воспроизводимы:
        // без неё все три модели отвечают за ноль миллисекунд и сравнивать нечего.
        Thread.sleep(profile.sleepMs)

        return LlmExchange(
            url = "https://openrouter.ai/api/v1/chat/completions",
            method = "POST",
            requestHeaders = mapOf("Authorization" to "Bearer sk-or-v1-…4242"),
            requestBody = """{"model":"${request.model}"}""",
            status = 200,
            responseBody = """{"choices":[{"message":{"content":"…"}}]}""",
            parsed = ChatCompletionResponse(
                id = "test",
                model = request.model,
                provider = profile.provider,
                choices = listOf(
                    Choice(message = ApiMessage(role = "assistant", content = profile.answer), finishReason = "stop"),
                ),
                usage = Usage(
                    promptTokens = PROMPT_TOKENS,
                    completionTokens = profile.completionTokens,
                    totalTokens = PROMPT_TOKENS + profile.completionTokens,
                    cost = profile.cost.takeUnless { omitCost },
                    completionTokensDetails = CompletionTokensDetails(profile.reasoningTokens),
                ),
            ),
        )
    }

    private data class Profile(
        val sleepMs: Long,
        val completionTokens: Int,
        val cost: Double,
        val answer: String,
        val reasoningTokens: Int = 0,
        val provider: String? = null,
    )

    private companion object {
        const val PROMPT_TOKENS = 30

        val PROFILES = mapOf(
            "meta-llama/llama-3.2-1b-instruct" to Profile(
                sleepMs = 20, completionTokens = 40, cost = 0.0000102,
                answer = "Мяч стоит 100 рублей.", provider = "Together",
            ),
            "deepseek/deepseek-chat" to Profile(
                sleepMs = 60, completionTokens = 180, cost = 0.000175,
                answer = "Обозначим мяч за x. Тогда x + (x + 1000) = 1100, значит мяч стоит 50 рублей.",
                provider = "DeepSeek",
            ),
            "anthropic/claude-sonnet-5" to Profile(
                sleepMs = 140, completionTokens = 320, cost = 0.00326, reasoningTokens = 120,
                answer = "Ловушка условия в том, что 1100 хочется разделить как 1000 и 100. " +
                    "Пусть мяч стоит x, бита x + 1000. Сумма 2x + 1000 = 1100, x = 50 рублей.",
                provider = "Anthropic",
            ),
        )
    }
}

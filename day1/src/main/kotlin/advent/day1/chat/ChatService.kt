package advent.day1.chat

import advent.day1.config.DeepSeekProperties
import advent.day1.llm.ApiMessage
import advent.day1.llm.ChatCompletionRequest
import advent.day1.llm.LlmClient
import advent.day1.llm.LlmException
import advent.day1.web.ChatRequest
import advent.day1.web.ChatResponse
import advent.day1.web.UsageView
import advent.day1.web.toView
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Прикладная логика чата: выбор модели, сборка запроса к LLM, разбор ответа.
 * Здесь же в дальнейшем появится история диалога — контроллеру менять не придётся.
 */
@Service
class ChatService(
    private val client: LlmClient,
    private val properties: DeepSeekProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun ask(request: ChatRequest): ChatResponse {
        val model = resolveModel(request.model)
        val params = request.params

        val apiRequest = ChatCompletionRequest(
            model = model,
            messages = listOf(ApiMessage(role = "user", content = request.prompt)),
            temperature = params?.temperature,
            topP = params?.topP,
            maxTokens = params?.maxTokens,
            frequencyPenalty = params?.frequencyPenalty,
            presencePenalty = params?.presencePenalty,
            stop = params?.stop?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() },
        )

        val startedAt = System.nanoTime()
        val exchange = client.complete(apiRequest)
        val latencyMs = (System.nanoTime() - startedAt) / 1_000_000

        val completion = exchange.parsed
            ?: throw LlmException("LLM вернул тело, которое не удалось разобрать", exchange = exchange)
        val choice = completion.choices.firstOrNull()
            ?: throw LlmException("LLM не вернул ни одного варианта ответа", exchange = exchange)
        val answer = choice.message?.content
            ?: throw LlmException("LLM вернул ответ без текста", exchange = exchange)

        log.info(
            "LLM ok: model={}, finish={}, tokens={}, latency={}ms",
            completion.model, choice.finishReason, completion.usage?.totalTokens, latencyMs,
        )

        return ChatResponse(
            answer = answer,
            reasoning = choice.message.reasoningContent,
            model = completion.model ?: model,
            finishReason = choice.finishReason,
            usage = completion.usage?.let {
                UsageView(it.promptTokens, it.completionTokens, it.totalTokens)
            },
            latencyMs = latencyMs,
            appliedParams = appliedParams(apiRequest),
            exchange = exchange.toView(),
        )
    }

    fun allowedModels(): List<String> = properties.allowedModels.sorted()

    fun defaultModel(): String = properties.defaultModel

    /**
     * Модель выбирается только из белого списка: произвольная строка с фронта
     * не должна уходить в внешний API.
     */
    private fun resolveModel(requested: String?): String {
        val model = requested?.takeIf { it.isNotBlank() } ?: properties.defaultModel
        require(model in properties.allowedModels) {
            "Неизвестная модель '$model'. Доступны: ${allowedModels().joinToString(", ")}"
        }
        return model
    }

    private fun appliedParams(request: ChatCompletionRequest): Map<String, Any> = buildMap {
        request.temperature?.let { put("temperature", it) }
        request.topP?.let { put("top_p", it) }
        request.maxTokens?.let { put("max_tokens", it) }
        request.frequencyPenalty?.let { put("frequency_penalty", it) }
        request.presencePenalty?.let { put("presence_penalty", it) }
        request.stop?.let { put("stop", it) }
    }
}

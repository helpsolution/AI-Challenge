package advent.day3.llm

import advent.day3.config.DeepSeekProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Один поход к модели: собрать запрос, дождаться ответа, разобрать его.
 * Всё, что выше по стеку, оперирует уже [RunResult] и про HTTP не знает.
 *
 * Наследник ChatService из дней 1–2. Отличие: результат описан своими типами,
 * а не веб-DTO — способов рассуждения много, и каждый склеивает вызовы по-своему.
 */
@Service
class LlmRunner(
    private val client: LlmClient,
    private val properties: DeepSeekProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(spec: RunSpec): RunResult {
        val model = resolveModel(spec.model)

        val messages = buildList {
            spec.systemPrompt?.takeIf { it.isNotBlank() }?.let {
                add(ApiMessage(role = "system", content = it))
            }
            add(ApiMessage(role = "user", content = spec.prompt))
        }

        val apiRequest = ChatCompletionRequest(
            model = model,
            messages = messages,
            temperature = spec.temperature,
            maxTokens = spec.maxTokens,
        )

        val startedAt = System.nanoTime()
        val exchange = client.complete(apiRequest)
        val latencyMs = (System.nanoTime() - startedAt) / 1_000_000

        val completion = exchange.parsed
            ?: throw LlmException("LLM вернул тело, которое не удалось разобрать", exchange = exchange)
        val choice = completion.choices.firstOrNull()
            ?: throw LlmException("LLM не вернул ни одного варианта ответа", exchange = exchange)
        val answer = choice.message?.content?.takeIf { it.isNotBlank() }
            ?: throw LlmException("LLM вернул ответ без текста", exchange = exchange)

        log.info(
            "LLM ok: model={}, finish={}, tokens={}, latency={}ms",
            completion.model, choice.finishReason, completion.usage?.totalTokens, latencyMs,
        )

        return RunResult(
            answer = answer.trim(),
            reasoning = choice.message.reasoningContent,
            model = completion.model ?: model,
            finishReason = choice.finishReason,
            usage = completion.usage,
            latencyMs = latencyMs,
            exchange = exchange,
        )
    }

    fun allowedModels(): List<String> = properties.allowedModels.sorted()

    fun defaultModel(): String = properties.defaultModel

    /** Модель выбирается только из белого списка: произвольная строка с фронта не уходит во внешний API. */
    private fun resolveModel(requested: String?): String {
        val model = requested?.takeIf { it.isNotBlank() } ?: properties.defaultModel
        require(model in properties.allowedModels) {
            "Неизвестная модель '$model'. Доступны: ${allowedModels().joinToString(", ")}"
        }
        return model
    }
}

/** Что именно отправляем: текст запроса, необязательная системная инструкция и параметры генерации. */
data class RunSpec(
    val prompt: String,
    val systemPrompt: String? = null,
    val model: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
)

/** Что вернулось: текст ответа и всё, что нужно для отчёта — метрики и сырой обмен. */
data class RunResult(
    val answer: String,
    val reasoning: String? = null,
    val model: String,
    val finishReason: String? = null,
    val usage: Usage? = null,
    val latencyMs: Long,
    val exchange: LlmExchange,
)

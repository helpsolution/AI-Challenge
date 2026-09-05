package advent.day5.llm

import advent.day5.config.OpenRouterProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Один поход к модели: собрать запрос, дождаться ответа, разобрать его.
 * Всё, что выше по стеку, оперирует уже [RunResult] и про HTTP не знает.
 *
 * Наследник LlmRunner из дня 4. Разница одна: там менялась температура при общей модели,
 * здесь меняется модель при общих параметрах генерации.
 */
@Service
class LlmRunner(
    private val client: LlmClient,
    private val properties: OpenRouterProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(spec: RunSpec): RunResult {
        val model = checkAllowed(spec.model)

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
            ?: throw LlmException("$model вернула тело, которое не удалось разобрать", exchange = exchange)
        val choice = completion.choices.firstOrNull()
            ?: throw LlmException("$model не вернула ни одного варианта ответа", exchange = exchange)
        val answer = choice.message?.content?.takeIf { it.isNotBlank() }
            ?: throw LlmException(emptyAnswerMessage(model, choice), exchange = exchange)

        log.info(
            "Ответ получен: model={}, provider={}, finish={}, tokens={}, cost={}, latency={}ms",
            completion.model, completion.provider, choice.finishReason,
            completion.usage?.totalTokens, completion.usage?.cost, latencyMs,
        )

        return RunResult(
            answer = answer.trim(),
            reasoning = choice.message.reasoning,
            model = completion.model ?: model,
            provider = completion.provider,
            finishReason = choice.finishReason ?: choice.nativeFinishReason,
            usage = completion.usage,
            latencyMs = latencyMs,
            exchange = exchange,
        )
    }

    /**
     * Пустой content при finish_reason=length — не сбой связи, а исчерпанный потолок:
     * reasoning-модель успела потратить весь лимит на размышление и до ответа не дошла.
     * Подсказка про max_tokens экономит пользователю полчаса.
     */
    private fun emptyAnswerMessage(model: String, choice: Choice): String =
        if (choice.finishReason == "length") {
            "$model упёрлась в потолок длины, не начав ответ: весь лимит ушёл на рассуждение. " +
                "Поднимите «потолок ответа»."
        } else {
            "$model вернула ответ без текста (finish_reason=${choice.finishReason ?: "не указан"})"
        }

    /** Модель берётся только из каталога: произвольная строка с фронта во внешний API не уходит. */
    private fun checkAllowed(model: String): String {
        require(model.isNotBlank()) { "Не указана модель" }
        require(model in properties.catalog.map { it.id }.toSet()) {
            "Модель '$model' не входит в каталог"
        }
        return model
    }
}

/** Что именно отправляем: текст запроса, необязательная системная инструкция и параметры генерации. */
data class RunSpec(
    val prompt: String,
    val model: String,
    val systemPrompt: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
)

/** Что вернулось: текст ответа и всё, что нужно для замеров — токены, деньги, время, сырой обмен. */
data class RunResult(
    val answer: String,
    val reasoning: String? = null,
    val model: String,
    val provider: String? = null,
    val finishReason: String? = null,
    val usage: Usage? = null,
    val latencyMs: Long,
    val exchange: LlmExchange,
)

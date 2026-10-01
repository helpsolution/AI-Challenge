package advent.rag.rewrite

import advent.rag.agent.PromptBuilder
import advent.rag.llm.ChatMessage
import advent.rag.llm.Completion
import advent.rag.llm.LlmClient
import advent.rag.llm.LlmException
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import kotlin.time.TimeSource

@ConfigurationProperties("query-rewrite")
data class QueryRewriteProperties(val enabled: Boolean)

data class RewriteInfo(
    val enabled: Boolean, val input: String, val baselineQuery: String, val query: String,
    val status: String, val reason: String, val model: String?, val durationMs: Long,
) {
    val changed: Boolean get() = query != baselineQuery
}

data class QueryRewrite(val decision: RewriteInfo, val completion: Completion? = null)

@Component
class QueryRewriter(private val llm: LlmClient, private val prompts: PromptBuilder, private val json: JsonMapper,
                    private val properties: QueryRewriteProperties) {
    fun rewrite(question: String, history: List<ChatMessage>, enabled: Boolean? = null): QueryRewrite {
        val baseline = baselineQuery(question, history)
        if (!(enabled ?: properties.enabled)) return QueryRewrite(RewriteInfo(false, question, baseline, baseline,
            "disabled", "Переформулировка выключена; сохранена прежняя подготовка уточнений", null, 0))
        val started = TimeSource.Monotonic.markNow()
        val completion = try {
            llm.complete(prompts.rewrite(question, history), jsonResponse = true, maxTokens = 384, temperature = 0.0)
        } catch (e: LlmException) {
            return QueryRewrite(RewriteInfo(true, question, baseline, baseline, "fallback",
                "Переформулировка недоступна: ${e.message}".take(300), null, started.elapsedNow().inWholeMilliseconds))
        }
        val parsed = runCatching {
            require(completion.finishReason != "length") { "Модель не закончила переформулировку" }
            val node = json.readTree(completion.text)
            require(node.path("query").isString && node.path("reason").isString) { "Ожидались строки query и reason" }
            val query = node.path("query").stringValue().trim()
            require(query.isNotEmpty() && query.length <= 2000) { "Поисковый вопрос пустой или слишком длинный" }
            require(numbers(query).containsAll(numbers(question))) { "Переформулировка потеряла числовое ограничение" }
            require(percentiles(query).containsAll(percentiles(question))) { "Переформулировка потеряла обозначение перцентиля" }
            query to node.path("reason").stringValue().take(300)
        }
        val (query, reason) = parsed.getOrElse { baseline to "Переформулировка отклонена: ${it.message}".take(300) }
        val status = if (parsed.isFailure) "fallback" else if (query == baseline) "unchanged" else "rewritten"
        return QueryRewrite(RewriteInfo(true, question, baseline, query, status, reason, completion.model,
            started.elapsedNow().inWholeMilliseconds), completion)
    }

    companion object {
        private val FOLLOW_UP = Regex("(?iu)^(а\\s|и\\s|почему\\sэто|как\\sэто)|\\b(этот|этого|этом|это|его|него|них|такой|такая|эти)\\b")
        private val NUMBER = Regex("(?<![\\p{L}\\p{N}])\\d+(?:[.,]\\d+)?")
        private val GROUP_SPACE = Regex("(?<=\\d)[ \\u00a0\\u202f]+(?=\\d)")
        private val PERCENTILE = Regex("(?i)\\bp\\s*(\\d+(?:[.,]\\d+)?)\\b")

        fun baselineQuery(question: String, history: List<ChatMessage>): String {
            val previous = history.lastOrNull { it.role == "user" }?.content
            return if (previous != null && FOLLOW_UP.containsMatchIn(question) && question.length < 200)
                "Предыдущий вопрос: $previous\nУточнение: $question" else question
        }

        private fun numbers(text: String) = NUMBER.findAll(text.replace(GROUP_SPACE, ""))
            .map { it.value.replace(',', '.').toBigDecimal().stripTrailingZeros().toPlainString() }.toSet()
        private fun percentiles(text: String) = PERCENTILE.findAll(text).map { it.groupValues[1].replace(',', '.') }.toSet()
    }
}

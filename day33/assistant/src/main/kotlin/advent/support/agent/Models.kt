package advent.support.agent

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("agent")
data class AgentProperties(val maxSteps: Int, val historyTurns: Int)

data class HistoryItem(val role: String, val content: String)

// customerId в демо приходит из переключателя клиентов; в настоящем сервисе его берут из сессии, а не из тела запроса.
data class SupportRequest(val customerId: String, val message: String, val ticketId: Int? = null,
    val history: List<HistoryItem> = emptyList())

// kind: crm — вызов MCP, docs — поиск по документации, llm — ход модели, note — рассуждение модели, повтор или предупреждение.
data class Step(val kind: String, val title: String, val status: String, val durationMs: Long, val detail: Any?)

data class DocSource(val number: Int, val file: String, val title: String, val section: String?, val text: String,
    val score: Double)

// Действие с записью в CRM, которое модель предложила, но выполнит только клиент кнопкой.
data class ProposedAction(val id: String, val tool: String, val title: String, val fields: Map<String, Any?>)

data class SupportAnswer(
    val status: String,
    val text: String,
    val customerId: String,
    val ticketId: Int?,
    val sources: List<DocSource>,
    val cited: List<Int>,
    val action: ProposedAction?,
    val llmCalls: Int,
    val toolCalls: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val costUsd: Double,
    val model: String,
    val durationMs: Long,
    val warnings: List<String>,
)

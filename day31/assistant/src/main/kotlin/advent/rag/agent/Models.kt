package advent.rag.agent

import com.fasterxml.jackson.annotation.JsonValue
import advent.rag.grounding.Quote
import advent.rag.llm.ChatMessage

enum class AnswerStatus(@get:JsonValue val code: String) {
    ANSWERED("answered"), WEAK_CONTEXT("weak_context"), NO_ANSWER("no_answer"),
    UNVERIFIED("unverified"), FAILED("failed"), CLARIFY("clarify"), HELP("help")
}
data class HelpRequest(val message: String, val scope: String = "repo", val history: List<ChatMessage> = emptyList())
data class Source(val number: Int, val kind: String, val source: String, val section: String?,
    val text: String, val score: Double?, val lineStart: Int?, val lineEnd: Int?, val capturedAt: String? = null)
data class ToolProof(val name: String, val arguments: Map<String, Any>, val data: Map<String, Any?>?,
    val error: String?, val durationMs: Long)
data class HelpAnswer(val id: String, val scope: String, val question: String, val status: AnswerStatus,
    val answer: String, val reason: String, val branch: String?, val sources: List<Source>,
    val quotes: List<Quote>, val tools: List<ToolProof>, val durationMs: Long,
    val model: String?, val promptTokens: Int? = null, val completionTokens: Int? = null,
    val warnings: List<String> = emptyList())

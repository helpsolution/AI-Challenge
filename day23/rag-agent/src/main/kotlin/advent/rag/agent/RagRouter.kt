package advent.rag.agent

import advent.rag.llm.ChatMessage
import advent.rag.llm.Completion
import advent.rag.llm.LlmClient
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

data class RouteDecision(val requestedMode: String, val useRag: Boolean, val reason: String, val durationMs: Long = 0)
data class Routing(val decision: RouteDecision, val completion: Completion? = null)

@Component
class RagRouter(private val llm: LlmClient, private val prompts: PromptBuilder, private val json: JsonMapper) {
    fun decide(mode: String, question: String, history: List<ChatMessage>, titles: List<String>): Routing {
        if (mode != "auto") return Routing(RouteDecision(mode, mode == "rag", "Режим выбран пользователем"))
        val completion = llm.complete(prompts.routing(question, history, titles), jsonResponse = true)
        val decision = runCatching {
            val node = json.readTree(completion.text)
            require(node.path("useRag").isBoolean && node.path("reason").isString)
            RouteDecision(mode, node.path("useRag").booleanValue(), node.path("reason").stringValue().take(300), completion.exchange.durationMs)
        }.getOrElse {
            RouteDecision(mode, true, "Не удалось разобрать решение модели; выполняю поиск по лекциям", completion.exchange.durationMs)
        }
        return Routing(decision, completion)
    }
}

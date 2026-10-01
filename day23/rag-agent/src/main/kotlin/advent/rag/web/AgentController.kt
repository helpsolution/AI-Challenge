package advent.rag.web

import advent.rag.agent.Agent
import advent.rag.agent.AgentAnswer
import advent.rag.llm.ChatMessage
import org.springframework.web.bind.annotation.*

data class AskRequest(val question: String, val mode: String? = null, val history: List<ChatMessage> = emptyList(),
                      val rag: Boolean? = null, val rerank: Boolean? = null, val rewrite: Boolean? = null)

@RestController
@RequestMapping("/api")
class AgentController(private val agent: Agent) {
    @PostMapping("/ask")
    fun ask(@RequestBody request: AskRequest): AgentAnswer =
        agent.ask(request.question, request.mode ?: request.rag?.let { if (it) "rag" else "plain" } ?: "auto", request.history, request.rerank, request.rewrite)
}

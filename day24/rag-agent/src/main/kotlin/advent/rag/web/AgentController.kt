package advent.rag.web

import advent.rag.agent.Agent
import advent.rag.agent.AgentAnswer
import advent.rag.llm.ChatMessage
import org.springframework.web.bind.annotation.*

data class AskRequest(val question: String, val history: List<ChatMessage> = emptyList())

@RestController
@RequestMapping("/api")
class AgentController(private val agent: Agent) {
    @PostMapping("/ask")
    fun ask(@RequestBody request: AskRequest): AgentAnswer = agent.ask(request.question, request.history)
}

package advent.rag.web

import advent.rag.agent.Agent
import advent.rag.agent.AgentAnswer
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Тело POST /api/ask: вопрос и галочка «искать в базе знаний». */
data class AskRequest(val question: String, val rag: Boolean)

@RestController
@RequestMapping("/api")
class AgentController(private val agent: Agent) {

    @PostMapping("/ask")
    fun ask(@RequestBody request: AskRequest): AgentAnswer = agent.ask(request.question, request.rag)
}

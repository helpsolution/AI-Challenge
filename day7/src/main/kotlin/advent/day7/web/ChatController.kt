package advent.day7.web

import advent.day7.agent.Agent
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Тонкий слой между браузером и агентом: ничего не решает сам.
 *
 * Историю отдаёт сервер, а не хранит вкладка. Поэтому открытая заново страница показывает
 * тот же диалог — как и страница, открытая после перезапуска приложения.
 */
@RestController
@RequestMapping("/api/chat")
class ChatController(private val agent: Agent) {

    @GetMapping
    fun chat(): ChatView = ChatView(agent.view(), agent.history())

    @PostMapping
    fun ask(@RequestBody request: AskRequest): AskResponse {
        val answer = agent.ask(request.text)
        return AskResponse(answer, agent.remembered())
    }

    @DeleteMapping
    fun forget(): ChatView {
        agent.forget()
        return ChatView(agent.view(), agent.history())
    }

    private fun Agent.view() = AgentView(settings.name, settings.model, remembered())
}

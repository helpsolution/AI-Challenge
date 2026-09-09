package advent.day8.web

import advent.day8.agent.Agent
import advent.day8.chat.totals
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Тонкий слой между браузером и агентом: ничего не решает сам.
 *
 * И переписку, и расход отдаёт сервер, а не хранит вкладка. Поэтому открытая заново
 * страница показывает тот же диалог и тот же график — как и страница, открытая после
 * перезапуска приложения.
 */
@RestController
@RequestMapping("/api/chat")
class ChatController(private val agent: Agent) {

    @GetMapping
    fun chat(): ChatView = agent.chatView()

    @PostMapping
    fun ask(@RequestBody request: AskRequest): AskResponse {
        val exchange = agent.ask(request.text)
        return AskResponse(
            agent = agent.view(),
            answer = exchange.answer,
            turn = exchange.turn,
            totals = agent.turns().totals(),
            remembered = agent.remembered(),
        )
    }

    @DeleteMapping
    fun forget(): ChatView {
        agent.forget()
        return agent.chatView()
    }

    private fun Agent.chatView(): ChatView {
        val turns = turns()
        return ChatView(
            agent = view(),
            messages = history(),
            turns = turns,
            totals = turns.totals(),
        )
    }

    private fun Agent.view() = AgentView(
        name = settings.name,
        model = settings.model,
        contextLimit = settings.contextLimit,
        maxTokens = settings.maxTokens,
        contextForPrompt = settings.contextForPrompt,
        remembered = remembered(),
    )
}

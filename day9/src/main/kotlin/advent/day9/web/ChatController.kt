package advent.day9.web

import advent.day9.agent.Agent
import advent.day9.chat.totals
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Тонкий слой между браузером и агентом: ничего не решает сам.
 *
 * И переписку, и конспект, и расход отдаёт сервер, а не хранит вкладка. Поэтому открытая
 * заново страница показывает тот же диалог, тот же конспект и тот же график — как и
 * страница, открытая после перезапуска приложения.
 */
@RestController
@RequestMapping("/api/chat")
class ChatController(private val agent: Agent) {

    @GetMapping
    fun chat(): ChatView = agent.chatView()

    @PostMapping
    fun ask(@RequestBody request: AskRequest): AskResponse {
        val exchange = agent.ask(request.text)
        val turns = agent.turns()
        return AskResponse(
            agent = agent.view(),
            answer = exchange.answer,
            turns = turns,
            totals = turns.totals(),
            summary = agent.summary(),
            summaries = agent.summaries(),
            folds = exchange.folds.size,
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
            summary = summary(),
            summaries = summaries(),
        )
    }

    private fun Agent.view() = AgentView(
        name = settings.name,
        model = settings.model,
        contextLimit = settings.contextLimit,
        maxTokens = settings.maxTokens,
        contextForPrompt = settings.contextForPrompt,
        remembered = remembered(),
        mode = settings.context.mode,
        keepLast = settings.context.keepLast,
        summarizeEvery = settings.context.summarizeEvery,
        summaryMaxTokens = settings.context.summaryMaxTokens,
        summaryModel = settings.summaryModel,
    )
}

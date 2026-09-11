package advent.day10.web

import advent.day10.agent.Agent
import advent.day10.chat.Session
import advent.day10.chat.totals
import advent.day10.context.StrategyRegistry
import advent.day10.store.ChatStore
import advent.day10.store.FactStore
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Тонкий слой между браузером и агентом: ничего не решает сам.
 *
 * И переписку, и замеры отдаёт сервер, а не хранит вкладка. Поэтому открытая заново
 * страница показывает те же диалоги и те же числа — как и страница, открытая после
 * перезапуска приложения.
 */
@RestController
@RequestMapping("/api")
class ChatController(
    private val agent: Agent,
    private val store: ChatStore,
    private val facts: FactStore,
    private val strategies: StrategyRegistry,
) {

    @GetMapping("/agent")
    fun agent(): AgentView = with(agent.config) {
        AgentView(
            name = name,
            model = model,
            contextLimit = contextLimit,
            maxTokens = maxTokens,
            contextForPrompt = contextForPrompt,
            defaultStrategy = defaultStrategy,
            defaultWindowSize = defaultWindowSize,
            strategies = strategies.available.sorted(),
        )
    }

    @GetMapping("/sessions")
    fun sessions(): List<SessionSummary> = store.sessions().map { session ->
        val turns = store.turns(session.id)
        SessionSummary(
            session = session,
            messages = store.countMessages(session.id),
            turns = turns.size,
            totals = turns.totals(),
        )
    }

    @PostMapping("/sessions")
    fun create(@RequestBody request: CreateSessionRequest): Session =
        agent.createSession(request.title, request.strategy, request.windowSize)

    @GetMapping("/sessions/{id}")
    fun session(@PathVariable id: Long): SessionView {
        val session = store.requireSession(id)
        val turns = store.turns(id)
        return SessionView(
            session = session,
            messages = store.history(id),
            turns = turns,
            totals = turns.totals(),
            branches = store.branches(id),
            facts = facts.facts(id),
        )
    }

    @PostMapping("/sessions/{id}/messages")
    fun ask(@PathVariable id: Long, @RequestBody request: AskRequest): AskResponse {
        val exchange = agent.ask(id, request.text)
        return AskResponse(
            question = exchange.question,
            answer = exchange.answer,
            turn = exchange.turn,
            totals = store.turns(id).totals(),
        )
    }

    /**
     * Развилка: та же история до выбранного сообщения, дальше — независимое продолжение.
     *
     * Заголовок по умолчанию говорит, откуда ветка растёт, а не «Ветка 1»: через полчаса
     * экспериментов список из пяти «Веток» не сообщает ничего.
     */
    @PostMapping("/sessions/{id}/fork")
    fun fork(@PathVariable id: Long, @RequestBody request: ForkRequest): Session {
        val parent = store.requireSession(id)
        val title = request.title?.trim()?.takeIf { it.isNotEmpty() } ?: "Ветка от «${parent.title}»"
        return store.fork(id, request.afterMessageId, title)
    }

    @DeleteMapping("/sessions/{id}")
    fun delete(@PathVariable id: Long) = store.deleteSession(id)
}

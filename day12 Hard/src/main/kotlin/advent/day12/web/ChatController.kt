package advent.day12.web

import advent.day12.agent.Agent
import advent.day12.agent.MemoryRouter
import advent.day12.inspection.AgentInspection
import advent.day12.chat.Session
import advent.day12.memory.LongTermMemory
import advent.day12.memory.NewMemoryItem
import advent.day12.memory.ShortTermMemory
import advent.day12.memory.WorkingMemory
import advent.day12.profile.Profile
import advent.day12.profile.ProfileStore
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.PutMapping

@RestController
@RequestMapping("/api")
class ChatController(
    private val agent: Agent,
    private val shortTerm: ShortTermMemory,
    private val working: WorkingMemory,
    private val longTerm: LongTermMemory,
    private val memoryRouter: MemoryRouter,
    private val profiles: ProfileStore,
    private val inspection: AgentInspection,
) {
    @GetMapping("/agent")
    fun agent(): AgentView = with(agent.properties) {
        AgentView(
            name = name,
            model = model,
            maxTokens = maxTokens,
            temperature = temperature,
            defaultWindowSize = windowSize,
            longTermLimit = longTermLimit,
        )
    }

    @GetMapping("/sessions")
    fun sessions(): List<SessionSummary> = shortTerm.sessions().map {
        SessionSummary(
            session = it,
            messages = shortTerm.countMessages(it.id),
            working = working.get(it.id),
            profile = it.profileId?.let(profiles::profile),
        )
    }

    @PostMapping("/sessions")
    fun create(@RequestBody request: CreateSessionRequest): Session =
        agent.createSession(request.title, request.windowSize)

    @GetMapping("/sessions/{id}")
    fun session(@PathVariable id: Long): SessionView {
        val session = shortTerm.requireSession(id)
        return SessionView(
            session = session,
            messages = shortTerm.history(id),
            working = working.get(id),
            longTerm = longTerm.list(agent.properties.longTermLimit),
            profile = session.profileId?.let(profiles::profile),
        )
    }

    @PostMapping("/sessions/{id}/messages")
    fun ask(@PathVariable id: Long, @RequestBody request: AskRequest): AskResponse {
        val result = agent.ask(id, request.text)
        return AskResponse(
            question = result.exchange.question,
            answer = result.exchange.answer,
            working = result.routing.working ?: working.get(id),
            longTermSaved = result.routing.longTerm,
        )
    }

    @DeleteMapping("/sessions/{id}")
    fun deleteSession(@PathVariable id: Long) = shortTerm.deleteSession(id)

    @PostMapping("/sessions/{id}/style-suggestion")
    fun acceptStyleSuggestion(@PathVariable id: Long): advent.day12.memory.MemoryItem {
        shortTerm.requireSession(id)
        return inspection.memoryAction(id, "Подтверждение правила стиля") { memoryRouter.acceptStyleSuggestion(id) }
    }

    @DeleteMapping("/sessions/{id}/style-suggestion")
    fun dismissStyleSuggestion(@PathVariable id: Long) {
        shortTerm.requireSession(id)
        inspection.memoryAction(id, "Отклонение правила стиля") { memoryRouter.dismissStyleSuggestion(id) }
    }

    @GetMapping("/memory/long-term")
    fun longTerm(): List<advent.day12.memory.MemoryItem> =
        longTerm.list(agent.properties.longTermLimit)

    @PostMapping("/memory/long-term")
    fun upsertLongTerm(@RequestBody request: UpsertMemoryRequest): advent.day12.memory.MemoryItem =
        longTerm.upsert(
            NewMemoryItem(
                kind = request.kind,
                key = request.key.trim().also { require(it.isNotEmpty()) { "Укажи название правила" } },
                value = request.value.trim().also { require(it.isNotEmpty()) { "Укажи значение правила" } },
                confidence = request.confidence.coerceIn(0.0, 1.0),
            ),
        )

    @DeleteMapping("/memory/long-term/{id}")
    fun deleteLongTerm(@PathVariable id: Long) = longTerm.deleteItem(id)

    @GetMapping("/profiles")
    fun profiles(): List<Profile> = profiles.profiles()

    @PostMapping("/profiles")
    fun createProfile(@RequestBody request: ProfileRequest): Profile =
        profiles.createProfile(cleanName(request.name), cleanDescription(request.description))

    @PutMapping("/profiles/{id}")
    fun updateProfile(@PathVariable id: Long, @RequestBody request: ProfileRequest): Profile =
        profiles.updateProfile(id, cleanName(request.name), cleanDescription(request.description))

    @DeleteMapping("/profiles/{id}")
    fun deleteProfile(@PathVariable id: Long) = profiles.deleteProfile(id)

    private fun cleanName(value: String): String =
        value.trim().also { require(it.isNotEmpty()) { "Укажи название профиля" } }.take(80)

    private fun cleanDescription(value: String): String =
        value.trim().also { require(it.isNotEmpty()) { "Укажи описание профиля" } }.take(2_000)
}

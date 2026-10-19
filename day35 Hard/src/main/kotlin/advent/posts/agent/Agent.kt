package advent.posts.agent

import advent.posts.chat.Exchange
import advent.posts.chat.Role
import advent.posts.chat.Session
import advent.posts.config.AgentProperties
import advent.posts.llm.ChatCompletionRequest
import advent.posts.llm.LlmClient
import advent.posts.llm.LlmException
import advent.posts.llm.ResponseFormat
import advent.posts.memory.ShortTermMemory
import advent.posts.memory.TaskMemory
import advent.posts.memory.WorkingMemory
import advent.posts.profile.Profile
import advent.posts.profile.ProfileStore
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import java.time.Clock

class Agent(
    private val llm: LlmClient,
    private val shortTerm: ShortTermMemory,
    private val working: WorkingMemory,
    private val profiles: ProfileStore,
    private val promptBuilder: PromptBuilder,
    private val memoryRouter: MemoryRouter,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    val properties: AgentProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun createSession(title: String?, windowSize: Int?): Session {
        val size = windowSize ?: properties.windowSize
        require(size > 0) { "Размер окна должен быть положительным" }
        val name = title?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_TITLE
        val session = shortTerm.createSession(name, size)
        working.save(TaskMemory(sessionId = session.id, updatedAt = clock.instant()))
        shortTerm.saveMessage(
            session.id,
            Role.ASSISTANT,
            profileQuestion(),
        )
        return session
    }

    fun ask(sessionId: Long, question: String): AgentExchange {
        val text = question.trim()
        require(text.isNotEmpty()) { "Пустое сообщение - отвечать нечего" }
        val session = shortTerm.requireSession(sessionId)

        if (session.profileId == null) {
            return selectProfile(sessionId, text)
        }

        val prompt = promptBuilder.build(sessionId, text)
        log.debug(
            "Сессия {}: prompt {} блоков, {} символов, profile {}, short-term {}, long-term {}, working {}",
            sessionId,
            prompt.messages.size,
            prompt.charsSent,
            prompt.snapshot.profile?.name,
            prompt.snapshot.shortTerm.size,
            prompt.snapshot.longTerm.size,
            prompt.snapshot.working != null,
        )

        val completion = llm.complete(
            ChatCompletionRequest(
                model = properties.model,
                messages = prompt.messages,
                temperature = properties.temperature,
                maxTokens = properties.maxTokens,
                responseFormat = ResponseFormat.JSON,
            ),
        )

        val decision = try {
            objectMapper.readValue(completion.content, AgentDecision::class.java)
        } catch (e: Exception) {
            throw LlmException("Модель вернула некорректное состояние поста", providerBody = completion.content)
        }
        val reply = decision.reply.trim().takeIf { it.isNotEmpty() }
            ?: throw LlmException("Модель вернула пустой ответ", providerBody = completion.content)

        val questionMessage = shortTerm.saveMessage(sessionId, Role.USER, text)
        val answerMessage = shortTerm.saveMessage(sessionId, Role.ASSISTANT, reply)
        val routing = memoryRouter.observe(sessionId, text, decision)
        val topic = routing.working?.let { it.idea ?: it.thesis ?: it.draft }
        if (session.title == DEFAULT_TITLE && topic != null) {
            shortTerm.renameSession(sessionId, titleFrom(topic))
        }

        return AgentExchange(
            exchange = Exchange(questionMessage, answerMessage),
            routing = routing,
        )
    }

    private fun selectProfile(sessionId: Long, text: String): AgentExchange {
        val available = profiles.profiles()
        val selected = resolveProfile(text, available)
        val reply = if (selected != null) {
            shortTerm.setProfile(sessionId, selected.id)
            "Выбран профиль «${selected.name}». Теперь расскажи, о чем хочешь написать пост."
        } else {
            "Не смог однозначно выбрать профиль. ${profileQuestion()}"
        }
        val questionMessage = shortTerm.saveMessage(sessionId, Role.USER, text)
        val answerMessage = shortTerm.saveMessage(sessionId, Role.ASSISTANT, reply)
        return AgentExchange(
            exchange = Exchange(questionMessage, answerMessage),
            routing = MemoryRoutingResult(working.get(sessionId), emptyList()),
        )
    }

    private fun resolveProfile(text: String, available: List<Profile>): Profile? {
        val normalizedText = normalize(text)
        val exact = available.filter { normalize(it.name) == normalizedText }
        if (exact.size == 1) return exact.single()
        return available.filter { normalizedText.contains(normalize(it.name)) }.singleOrNull()
    }

    private fun profileQuestion(): String {
        val names = profiles.profiles().joinToString(", ") { "«${it.name}»" }
        return if (names.isEmpty()) {
            "Перед началом создай хотя бы один профиль."
        } else {
            "У тебя есть профили: $names. Через какую призму будем писать этот пост? Выбери профиль кнопкой или напиши его название."
        }
    }

    private fun normalize(value: String): String = value.trim().lowercase().replace('ё', 'е')

    /** Пост без названия получает его из идеи (тезиса, черновика), чтобы список не состоял из одинаковых строк. */
    private fun titleFrom(topic: String): String {
        val line = topic.lineSequence().first().trim()
        if (line.length <= TITLE_LENGTH) return line
        return line.take(TITLE_LENGTH).substringBeforeLast(' ').trimEnd(',', '.', ':', ';', ' ') + "…"
    }

    private companion object {
        const val DEFAULT_TITLE = "Новый пост"
        const val TITLE_LENGTH = 60
    }
}

data class AgentExchange(
    val exchange: Exchange,
    val routing: MemoryRoutingResult,
)

package advent.day13.agent

import advent.day13.chat.Exchange
import advent.day13.chat.Role
import advent.day13.chat.Session
import advent.day13.config.AgentProperties
import advent.day13.inspection.AgentInspection
import advent.day13.llm.ChatCompletionRequest
import advent.day13.llm.LlmClient
import advent.day13.llm.LlmException
import advent.day13.llm.ResponseFormat
import advent.day13.memory.ShortTermMemory
import advent.day13.memory.TaskMemory
import advent.day13.memory.WorkingMemory
import advent.day13.profile.Profile
import advent.day13.profile.ProfileStore
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
    private val inspection: AgentInspection? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun createSession(title: String?, windowSize: Int?): Session {
        val size = windowSize ?: properties.windowSize
        require(size > 0) { "Размер окна должен быть положительным" }
        val name = title?.trim()?.takeIf { it.isNotEmpty() } ?: "Новый пост"
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
        var trace = inspection?.begin(sessionId, text)

        try {
        if (session.profileId == null) {
            trace = trace?.let { inspection?.event(it, "profileSelect", "Выбор профиля без вызова модели") }
            val result = selectProfile(sessionId, text)
            trace = trace?.let { inspection?.event(it, "output", "Профиль обработан, реплики сохранены") }
            trace?.let { inspection?.finish(it) }
            return result
        }

        trace = trace?.let { inspection?.event(it, "prompt", "Чтение профиля и трёх слоёв памяти") }
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

        val request = ChatCompletionRequest(
                model = properties.model,
                messages = prompt.messages,
                temperature = properties.temperature,
                maxTokens = properties.maxTokens,
                responseFormat = ResponseFormat.JSON,
            )
        trace = trace?.let { inspection?.event(it.copy(prompt = prompt, request = request), "llm", "Запрос передан LLM-клиенту") }
        val completion = llm.complete(request)
        // Store the returned content and usage, never internal reasoning or transport credentials.
        trace = trace?.let { inspection?.event(it.copy(completion = completion.copy(reasoning = null)), "parser", "Ответ получен, проверка JSON") }

        val decision = try {
            objectMapper.readValue(completion.content, AgentDecision::class.java)
        } catch (e: Exception) {
            throw LlmException("Модель вернула некорректное состояние поста", providerBody = completion.content)
        }
        val reply = decision.reply.trim().takeIf { it.isNotEmpty() }
            ?: throw LlmException("Модель вернула пустой ответ", providerBody = completion.content)

        trace = trace?.let { inspection?.event(it.copy(decision = decision), "router", "Сохранение реплик и применение изменений памяти") }
        val questionMessage = shortTerm.saveMessage(sessionId, Role.USER, text)
        val answerMessage = shortTerm.saveMessage(sessionId, Role.ASSISTANT, reply)
        val routing = memoryRouter.observe(sessionId, text, decision)
        trace = trace?.let { inspection?.event(it, "output", "Ответ готов, память сохранена") }
        trace?.let { inspection?.finish(it) }

        return AgentExchange(
            exchange = Exchange(questionMessage, answerMessage),
            routing = routing,
        )
        } catch (e: Exception) {
            trace?.let { inspection?.finish(it, e.message ?: "Ошибка обработки запроса") }
            throw e
        }
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
            "У тебя есть профили: $names. Через какую призму будем писать этот пост? Ответь названием профиля."
        }
    }

    private fun normalize(value: String): String = value.trim().lowercase().replace('ё', 'е')
}

data class AgentExchange(
    val exchange: Exchange,
    val routing: MemoryRoutingResult,
)

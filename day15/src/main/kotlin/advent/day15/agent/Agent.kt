package advent.day15.agent

import advent.day15.chat.Exchange
import advent.day15.chat.Role
import advent.day15.chat.Session
import advent.day15.config.AgentProperties
import advent.day15.inspection.AgentInspection
import advent.day15.llm.ChatCompletionRequest
import advent.day15.llm.LlmClient
import advent.day15.llm.LlmException
import advent.day15.llm.ResponseFormat
import advent.day15.lifecycle.TaskLifecycle
import advent.day15.memory.ShortTermMemory
import advent.day15.memory.TaskMemory
import advent.day15.memory.WorkingMemory
import advent.day15.profile.Profile
import advent.day15.profile.ProfileStore
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
    private val invariantGuard: advent.day15.invariant.InvariantGuard = advent.day15.invariant.InvariantGuard(),
    private val lifecycle: TaskLifecycle = TaskLifecycle(),
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
            val proposedReply = decision.reply.trim().takeIf { it.isNotEmpty() }
                ?: throw LlmException("Модель вернула пустой ответ", providerBody = completion.content)

            val current = working.get(sessionId) ?: error("Рабочее состояние задачи не найдено")
            val proposed = memoryRouter.preview(sessionId, decision)
            val transition = lifecycle.evaluate(current, proposed, decision.transition, text, proposedReply)
            trace = trace?.let { inspection?.event(it.copy(decision = decision, transitionVerdict = transition.verdict),
                "transition", if (transition.verdict.allowed) transition.verdict.reason else "Переход отклонен: ${transition.verdict.reason}") }
            val invariantVerdict = if (transition.verdict.allowed) {
                invariantGuard.check(decision, transition.candidate)
            } else {
                null
            }
            val reply = when {
                !transition.verdict.allowed -> transition.verdict.reason
                invariantVerdict?.allowed == false -> invariantVerdict.reason
                else -> proposedReply
            }
            if (invariantVerdict != null) {
                trace = trace?.let { inspection?.event(it.copy(invariantVerdict = invariantVerdict),
                    "guard", if (invariantVerdict.allowed) "Инварианты соблюдены" else "Запрос отклонен: " + invariantVerdict.violations.joinToString()) }
            }
            trace = trace?.copy(deliveredReply = reply)
            val allowed = transition.verdict.allowed && invariantVerdict?.allowed == true
            if (allowed) {
                trace = trace?.let { inspection?.event(it, "router", "Сохранение реплик и применение изменений памяти") }
            }
            val questionMessage = shortTerm.saveMessage(sessionId, Role.USER, text)
            val answerMessage = shortTerm.saveMessage(sessionId, Role.ASSISTANT, reply)
            val routing = if (allowed) memoryRouter.commit(text, transition.candidate)
                else MemoryRoutingResult(working.get(sessionId), emptyList())
            trace = trace?.let { inspection?.event(it, "output", if (allowed) "Ответ готов, память сохранена" else "Отказ сохранен, состояние поста не изменено") }
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

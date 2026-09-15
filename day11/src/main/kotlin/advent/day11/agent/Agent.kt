package advent.day11.agent

import advent.day11.chat.Exchange
import advent.day11.chat.Role
import advent.day11.chat.Session
import advent.day11.config.AgentProperties
import advent.day11.llm.ChatCompletionRequest
import advent.day11.llm.LlmClient
import advent.day11.llm.LlmException
import advent.day11.llm.ResponseFormat
import advent.day11.memory.ShortTermMemory
import advent.day11.memory.TaskMemory
import advent.day11.memory.WorkingMemory
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import java.time.Clock

class Agent(
    private val llm: LlmClient,
    private val shortTerm: ShortTermMemory,
    private val working: WorkingMemory,
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
        val name = title?.trim()?.takeIf { it.isNotEmpty() } ?: "Новый пост"
        val session = shortTerm.createSession(name, size)
        working.save(TaskMemory(sessionId = session.id, updatedAt = clock.instant()))
        shortTerm.saveMessage(
            session.id,
            Role.ASSISTANT,
            "О чем хочешь написать пост? Расскажи мысль как есть - вместе найдем ее главную точку.",
        )
        return session
    }

    fun ask(sessionId: Long, question: String): AgentExchange {
        val text = question.trim()
        require(text.isNotEmpty()) { "Пустое сообщение - отвечать нечего" }
        shortTerm.requireSession(sessionId)

        val prompt = promptBuilder.build(sessionId, text)
        log.debug(
            "Сессия {}: prompt {} блоков, {} символов, short-term {}, long-term {}, working {}",
            sessionId,
            prompt.messages.size,
            prompt.charsSent,
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

        return AgentExchange(
            exchange = Exchange(questionMessage, answerMessage),
            routing = routing,
        )
    }
}

data class AgentExchange(
    val exchange: Exchange,
    val routing: MemoryRoutingResult,
)

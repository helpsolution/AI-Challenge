package advent.rag.web

import advent.rag.agent.Agent
import advent.rag.agent.Turn
import advent.rag.chat.ChatProperties
import advent.rag.chat.ChatStore
import advent.rag.chat.Conversation
import advent.rag.chat.ConversationSummary
import advent.rag.memory.TaskMemory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

data class MessageRequest(val message: String)

/** Настройки, от которых зависит ответ: показываются в чате и попадают в результаты прогонов. */
data class ChatSettings(val memoryEnabled: Boolean, val historyTurns: Int)

@RestController
@RequestMapping("/api")
class ConversationController(private val agent: Agent, private val chats: ChatStore, private val memory: TaskMemory,
                             private val chat: ChatProperties) {
    @GetMapping("/settings")
    fun settings() = ChatSettings(memory.enabled, chat.historyTurns)

    @GetMapping("/conversations")
    fun list(): List<ConversationSummary> = chats.list()

    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(): Conversation = chats.create()

    @GetMapping("/conversations/{id}")
    fun get(@PathVariable id: String): Conversation = chats.get(id)

    @DeleteMapping("/conversations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: String) = chats.delete(id)

    @PostMapping("/conversations/{id}/messages")
    fun send(@PathVariable id: String, @RequestBody request: MessageRequest): Turn = agent.reply(id, request.message)
}

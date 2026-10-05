package advent.localchat.web

import advent.localchat.ollama.ChatMessage
import advent.localchat.ollama.ChatResult
import advent.localchat.ollama.ModelStatus
import advent.localchat.ollama.OllamaClient
import advent.localchat.ollama.OllamaProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** История диалога живёт в браузере и целиком приходит в каждом запросе: модель между вызовами ничего не помнит. */
data class AskRequest(val messages: List<ChatMessage>)

@RestController
@RequestMapping("/api")
class ChatController(private val ollama: OllamaClient, private val properties: OllamaProperties) {

    @GetMapping("/status")
    fun status(): ModelStatus = ollama.status()

    @PostMapping("/ask")
    fun ask(@RequestBody request: AskRequest): ChatResult {
        require(request.messages.isNotEmpty()) { "Нужно хотя бы одно сообщение" }
        val system = properties.systemPrompt.takeIf { it.isNotBlank() }?.let { ChatMessage("system", it) }
        return ollama.chat(listOfNotNull(system) + request.messages)
    }
}

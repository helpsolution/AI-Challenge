package advent.day2.web

import advent.day2.chat.ChatService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class ChatController(private val chatService: ChatService) {

    @PostMapping("/chat")
    fun chat(@Valid @RequestBody request: ChatRequest): ChatResponse = chatService.ask(request)

    @GetMapping("/models")
    fun models(): ModelsResponse = ModelsResponse(
        models = chatService.allowedModels(),
        defaultModel = chatService.defaultModel(),
    )
}

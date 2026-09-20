package advent.cookingstate.web

import advent.cookingstate.agent.CookingAgent
import advent.cookingstate.agent.CookingSession
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class MessageRequest(val text: String)

@RestController
@RequestMapping("/api/sessions")
@Tag(name = "Сессии приготовления")
class CookingController(private val agent: CookingAgent) {
    @PostMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Начать новую сессию")
    fun create(): CookingSession = agent.create()

    @GetMapping("/{id}", produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Посмотреть сохранённое состояние сессии")
    fun get(@PathVariable id: String): CookingSession = agent.get(id)

    @PostMapping("/{id}/messages", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Отправить сообщение агенту и обновить состояние")
    fun message(@PathVariable id: String, @RequestBody request: MessageRequest): CookingSession =
        agent.message(id, request.text)
}

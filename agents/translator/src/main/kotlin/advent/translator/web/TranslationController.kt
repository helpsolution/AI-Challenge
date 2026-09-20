package advent.translator.web

import advent.translator.agent.TranslationResult
import advent.translator.agent.TranslatorAgent
import advent.translator.config.TargetLanguage
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class TranslateRequest(
    @field:Schema(description = "Текст, который нужно перевести", example = "Привет, мир!", requiredMode = Schema.RequiredMode.REQUIRED)
    val text: String,
)

@RestController
@RequestMapping("/api")
@Tag(name = "Переводчик", description = "Ручная проверка агента и его языков")
class TranslationController(private val agent: TranslatorAgent) {
    @GetMapping("/languages", produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Получить языки перевода", description = "Возвращает языки из конфигурации приложения в порядке выдачи переводов.")
    fun languages(): List<TargetLanguage> = agent.languages()

    @PostMapping("/translations", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Перевести текст", description = "Отправляет текст в DeepSeek и возвращает по одному переводу на каждый настроенный язык.")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Переводы готовы", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = TranslationResult::class))]),
            ApiResponse(responseCode = "400", description = "Пустой или некорректный текст", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ErrorResponse::class))]),
            ApiResponse(responseCode = "502", description = "Ошибка DeepSeek или неполный ответ модели", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ErrorResponse::class))]),
        ],
    )
    fun translate(@RequestBody request: TranslateRequest): TranslationResult = agent.translate(request.text)
}

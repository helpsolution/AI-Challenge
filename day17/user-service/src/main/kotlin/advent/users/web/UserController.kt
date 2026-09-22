package advent.users.web

import advent.users.service.UserService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/users")
@Tag(name = "Пользователи", description = "Создание пользователя и поиск пользователя")
class UserController(private val service: UserService) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Создать пользователя",
        description = "Имя и email. Email нормализуется к нижнему регистру и должен быть уникальным.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Пользователь создан"),
        ApiResponse(
            responseCode = "400",
            description = "Пустое имя, кривой email или нечитаемое тело запроса",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "Пользователь с таким email уже есть",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun create(@RequestBody request: CreateUserRequest): User = service.create(request)

    @GetMapping
    @Operation(
        summary = "Найти пользователя",
        description = "Ищет вхождение подстроки в имени и в email без учёта регистра. " +
            "Возвращает список, возможно пустой — совпадений может быть несколько.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Список найденных пользователей, возможно пустой"),
        ApiResponse(
            responseCode = "400",
            description = "Пустой query или limit вне диапазона 1..100",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun search(
        @Parameter(description = "Часть имени или email", example = "иван")
        @RequestParam query: String,
        @Parameter(description = "Сколько результатов вернуть, 1..100", example = "20")
        @RequestParam(defaultValue = "20") limit: Int,
    ): List<User> = service.search(query, limit)
}

package advent.users.web

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Запрос на создание пользователя")
data class CreateUserRequest(
    @field:Schema(description = "Имя", example = "Иван Петров")
    val name: String,
    @field:Schema(description = "Email — он же уникальный ключ пользователя", example = "ivan@example.com")
    val email: String,
)

@Schema(description = "Пользователь")
data class User(
    @field:Schema(description = "Идентификатор", example = "1")
    val id: Long,
    @field:Schema(description = "Имя", example = "Иван Петров")
    val name: String,
    @field:Schema(description = "Email, приведённый к нижнему регистру", example = "ivan@example.com")
    val email: String,
    @field:Schema(description = "Момент создания в UTC", example = "2026-09-22T12:30:15Z")
    val createdAt: String,
)

@Schema(description = "Ошибка запроса")
data class ErrorResponse(
    @field:Schema(description = "Человекочитаемое объяснение", example = "Пользователь с email ivan@example.com уже существует")
    val message: String,
)

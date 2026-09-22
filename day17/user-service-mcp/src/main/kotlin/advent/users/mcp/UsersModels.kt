package advent.users.mcp

import kotlinx.serialization.Serializable

/** Ответы сервиса пользователей. Описаны один раз здесь — инструменты работают уже с готовыми объектами. */
@Serializable
data class User(
    val id: Long,
    val name: String,
    val email: String,
    val createdAt: String,
)

@Serializable
data class CreateUserRequest(
    val name: String,
    val email: String,
)

/** Тело ошибки сервиса пользователей. */
@Serializable
data class ApiError(val message: String)

package advent.users.service

import advent.users.storage.UserRepository
import advent.users.web.CreateUserRequest
import advent.users.web.User
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service

/** Бизнес-логика сервиса: нормализация, проверки и ровно две операции — создать и найти. */
@Service
class UserService(private val repository: UserRepository) {
    fun create(request: CreateUserRequest): User {
        val name = request.name.trim()
        require(name.isNotEmpty()) { "Поле name не может быть пустым" }
        require(name.length <= MAX_NAME_LENGTH) { "Поле name длиннее $MAX_NAME_LENGTH символов" }

        val email = request.email.trim().lowercase()
        require(email.isNotEmpty()) { "Поле email не может быть пустым" }
        require(email.length <= MAX_EMAIL_LENGTH) { "Поле email длиннее $MAX_EMAIL_LENGTH символов" }
        require(EMAIL_PATTERN.matches(email)) { "Значение \"${request.email}\" не похоже на email" }

        return try {
            repository.insert(name, email)
        } catch (e: DuplicateKeyException) {
            // Полагаемся на UNIQUE в схеме, а не на «сначала проверю, потом вставлю»:
            // проверка перед вставкой проигрывает гонку двум одновременным запросам.
            throw EmailAlreadyExistsException("Пользователь с email $email уже существует", e)
        }
    }

    fun search(query: String, limit: Int): List<User> {
        val trimmed = query.trim()
        require(trimmed.isNotEmpty()) { "Параметр query не может быть пустым" }
        require(limit in 1..MAX_LIMIT) { "Параметр limit должен быть от 1 до $MAX_LIMIT" }
        return repository.search(trimmed, limit)
    }

    private companion object {
        const val MAX_NAME_LENGTH = 100
        const val MAX_EMAIL_LENGTH = 254
        const val MAX_LIMIT = 100

        /** Не RFC 5322: задача этой проверки — отсечь очевидный мусор, а не валидировать почту целиком. */
        val EMAIL_PATTERN = Regex("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$")
    }
}

class EmailAlreadyExistsException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

package advent.day6.agent

import java.time.Instant

enum class Role(val apiName: String) { USER("user"), ASSISTANT("assistant") }

data class MemoryMessage(
    val role: Role,
    val content: String,
    val at: Instant,
)

/**
 * Память диалога. Хранит всё, что было сказано, — стенограмма нужна интерфейсу после
 * перезагрузки страницы. Но в промпт уходит не всё: [recall] отдаёт только последние
 * ходы в пределах окна. Разница между «помню» и «держу в голове прямо сейчас» — это
 * и есть та часть агента, которой нет у голого вызова API.
 */
class Memory {
    private val messages = mutableListOf<MemoryMessage>()

    val size: Int
        @Synchronized get() = messages.size

    @Synchronized
    fun remember(userText: String, askedAt: Instant, answer: String, answeredAt: Instant) {
        messages += MemoryMessage(Role.USER, userText, askedAt)
        messages += MemoryMessage(Role.ASSISTANT, answer, answeredAt)
    }

    /** Последние [windowTurns] ходов, по два сообщения на ход. Неполные ходы в памяти не бывают. */
    @Synchronized
    fun recall(windowTurns: Int): List<MemoryMessage> =
        if (windowTurns <= 0) emptyList() else messages.takeLast(windowTurns * 2)

    @Synchronized
    fun transcript(): List<MemoryMessage> = messages.toList()

    @Synchronized
    fun clear(): Int {
        val forgotten = messages.size
        messages.clear()
        return forgotten
    }
}

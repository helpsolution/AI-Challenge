package advent.news.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Консоль делят двое: чат и сводка по таймеру. Без общего замка сводка вклинилась бы
 * посреди ответа агента или между строк трассировки, и читать это было бы невозможно.
 *
 * Правило простое: печатает кто-то один. Сводка, пришедшая во время ответа, ждёт его конца;
 * сводка, пришедшая, пока человек думает над вопросом, печатается и возвращает приглашение «Вы:».
 */
class Console {
    private val mutex = Mutex()

    @Volatile
    private var waitingForInput = false

    /** Чтение блокирует поток, поэтому уходит на IO: иначе таймер сводки встал бы вместе с ним. */
    suspend fun readLine(): String? {
        mutex.withLock {
            print("\nВы: ")
            System.out.flush()
            waitingForInput = true
        }
        return withContext(Dispatchers.IO) { readlnOrNull() }.also { waitingForInput = false }
    }

    /** Вывод целым куском: пока он печатается, никто другой в консоль не пишет. */
    suspend fun exclusive(block: suspend () -> Unit) = mutex.withLock { block() }

    /** То же для вывода по таймеру: если человек в этот момент сидит на приглашении, оно печатается заново. */
    suspend fun interrupt(block: suspend () -> Unit) = mutex.withLock {
        val prompted = waitingForInput
        if (prompted) println()
        block()
        if (prompted) {
            print("\nВы: ")
            System.out.flush()
        }
    }
}

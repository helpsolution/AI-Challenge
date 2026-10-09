package advent.llmservice.limits

import advent.llmservice.api.ApiException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Очередь к модели: [parallel] запросов генерируются, до [maxWaiting] ждут, следующий сразу получает 503.
 * Без неё Ollama копит запросы у себя: соединения висят минутами, а клиент не знает, ждать ему или нет.
 */
class GenerationQueue(val parallel: Int, val maxWaiting: Int) {
    private val slots = Semaphore(parallel, true)
    private val waitingCount = AtomicInteger()
    private val activeCount = AtomicInteger()

    val active get() = activeCount.get()
    val waiting get() = waitingCount.get()

    /** Занимает место у модели, при необходимости дожидаясь его. Место освобождается закрытием [Slot]. */
    fun enter(): Slot {
        // tryAcquire с таймаутом соблюдает честность семафора: новичок не обгонит тех, кто уже ждёт.
        if (!slots.tryAcquire(0, TimeUnit.SECONDS)) {
            if (waitingCount.incrementAndGet() > maxWaiting) {
                waitingCount.decrementAndGet()
                throw ApiException.queueFull("Очередь полна: $parallel генерирует, $maxWaiting ждут. Повторите позже")
            }
            try {
                slots.acquire()
            } finally {
                waitingCount.decrementAndGet()
            }
        }
        activeCount.incrementAndGet()
        return Slot()
    }

    inner class Slot : AutoCloseable {
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            activeCount.decrementAndGet()
            slots.release()
        }
    }
}

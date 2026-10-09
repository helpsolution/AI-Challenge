package advent.llmservice.limits

import advent.llmservice.api.ApiException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Скользящее окно на ключ: не больше [limit] запросов за последние [window]. Считается каждая попытка с верным
 * ключом, даже если потом её отклонили очередь или окно контекста.
 */
class RateLimiter(val limit: Int, val window: Duration) {

    /** Остаток окна. [resetSeconds] — через сколько секунд освободится ближайшее место. */
    class Quota(val limit: Int, val remaining: Int, val resetSeconds: Long) {
        // Имена заголовков — как у OpenAI, их читают готовые клиенты.
        fun headers() = mapOf(
            "X-RateLimit-Limit-Requests" to "$limit",
            "X-RateLimit-Remaining-Requests" to "$remaining",
            "X-RateLimit-Reset-Requests" to "${resetSeconds}s",
        )
    }

    private val hits = ConcurrentHashMap<String, ArrayDeque<Long>>()

    /** Засчитывает запрос клиента или бросает 429, если окно уже заполнено. */
    fun take(client: String): Quota {
        val log = hits.computeIfAbsent(client) { ArrayDeque() }
        synchronized(log) {
            val now = System.currentTimeMillis()
            evict(log, now)
            if (log.size >= limit) {
                val quota = quota(log, now)
                throw ApiException.rateLimited(
                    "Лимит — $limit запросов за ${window.toSeconds()} с, он исчерпан. Повторите через ${quota.resetSeconds} с",
                    quota.headers() + ("Retry-After" to "${quota.resetSeconds}"),
                )
            }
            log.addLast(now)
            return quota(log, now)
        }
    }

    fun peek(client: String): Quota {
        val log = hits[client] ?: return Quota(limit, limit, 0)
        synchronized(log) {
            val now = System.currentTimeMillis()
            evict(log, now)
            return quota(log, now)
        }
    }

    private fun evict(log: ArrayDeque<Long>, now: Long) {
        while (log.isNotEmpty() && log.first() <= now - window.toMillis()) log.removeFirst()
    }

    private fun quota(log: ArrayDeque<Long>, now: Long): Quota {
        val reset = log.firstOrNull()?.let { (it + window.toMillis() - now + 999) / 1000 } ?: 0
        return Quota(limit, limit - log.size, reset)
    }
}

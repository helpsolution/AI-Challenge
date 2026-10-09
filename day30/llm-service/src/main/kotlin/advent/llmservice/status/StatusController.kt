package advent.llmservice.status

import advent.llmservice.LlmProperties
import advent.llmservice.limits.CLIENT
import advent.llmservice.limits.GenerationQueue
import advent.llmservice.limits.RateLimiter
import advent.llmservice.ollama.OllamaClient
import advent.llmservice.ollama.OllamaException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RestController
import java.io.File

data class ServiceStatus(
    val client: String,
    val model: String,
    val ollamaVersion: String?,
    val ollamaError: String?,
    /** Модель в памяти: сколько занимает и с каким окном. null — не загружена. */
    val loaded: LoadedModel?,
    val limits: Limits,
    val quota: Quota,
    val queue: Queue,
    /** Память машины из /proc/meminfo. null — не Linux. */
    val memory: HostMemory?,
    val counters: Map<Int, Long>,
    val recent: List<RequestRecord>,
)

data class LoadedModel(val sizeBytes: Long, val contextLength: Int?)

data class Limits(
    val contextTokens: Int,
    val maxOutputTokens: Int,
    val rateLimitRequests: Int,
    val rateLimitWindowSeconds: Long,
    val parallel: Int,
    val maxWaiting: Int,
)

data class Quota(val remaining: Int, val resetSeconds: Long)

data class Queue(val active: Int, val waiting: Int)

data class HostMemory(val totalBytes: Long, val availableBytes: Long, val swapTotalBytes: Long, val swapUsedBytes: Long)

/** Состояние сервиса для панели: модель, лимиты, очередь, остаток лимита у этого ключа и журнал. */
@RestController
class StatusController(
    private val props: LlmProperties,
    private val ollama: OllamaClient,
    private val rateLimiter: RateLimiter,
    private val queue: GenerationQueue,
    private val journal: RequestJournal,
) {

    @GetMapping("/api/status")
    fun status(@RequestAttribute(CLIENT) client: String): ServiceStatus {
        var version: String? = null
        var error: String? = null
        var loaded: LoadedModel? = null
        try {
            version = ollama.version()
            loaded = ollama.running().firstOrNull { it.name == props.model }?.let { LoadedModel(it.size, it.contextLength) }
        } catch (e: OllamaException) {
            error = e.message
        }
        val quota = rateLimiter.peek(client)
        return ServiceStatus(
            client = client,
            model = props.model,
            ollamaVersion = version,
            ollamaError = error,
            loaded = loaded,
            limits = Limits(
                props.contextTokens, props.maxOutputTokens, rateLimiter.limit, rateLimiter.window.toSeconds(),
                queue.parallel, queue.maxWaiting,
            ),
            quota = Quota(quota.remaining, quota.resetSeconds),
            queue = Queue(queue.active, queue.waiting),
            memory = hostMemory(),
            counters = journal.counters(),
            recent = journal.recent(),
        )
    }

    private fun hostMemory(): HostMemory? {
        val file = File("/proc/meminfo").takeIf { it.canRead() } ?: return null
        val kb = file.readLines().associate { line ->
            line.substringBefore(':') to (line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0L) * 1024
        }
        val swapTotal = kb["SwapTotal"] ?: 0
        return HostMemory(kb["MemTotal"] ?: 0, kb["MemAvailable"] ?: 0, swapTotal, swapTotal - (kb["SwapFree"] ?: 0))
    }
}

package advent.lab.experiment

import advent.lab.ollama.ChatReply
import advent.lab.ollama.ChatRequest
import advent.lab.ollama.OllamaClient
import advent.lab.ollama.OllamaException
import advent.lab.task.Scorer
import advent.lab.task.Ticket
import advent.lab.task.TicketTask
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.TimeSource

data class RunRequest(val name: String = "", val set: String = "tune", val repeats: Int = 1, val config: RunConfig)

/** Событие прогона для NDJSON-стрима: start → loaded → item… → done или error. */
typealias Emit = (Map<String, Any?>) -> Unit

@Component
class ExperimentRunner(
    private val ollama: OllamaClient,
    private val task: TicketTask,
    private val scorer: Scorer,
    private val store: ExperimentStore,
) {
    // Прогоны по очереди: два прогона на одном GPU замедлили бы друг друга, и замер скорости стал бы нечестным.
    private val busy = AtomicBoolean(false)

    /** Проверяет запрос и занимает очередь до начала стрима, чтобы ошибки пришли обычным HTTP-ответом. */
    fun prepare(request: RunRequest): (Emit) -> Unit {
        require(request.config.model.isNotBlank()) { "Выберите модель" }
        require(request.repeats >= 1) { "Нужен хотя бы один повтор" }
        val tickets = task.set(request.set)
        check(busy.compareAndSet(false, true)) { "Уже идёт прогон: дождитесь его окончания или остановите" }
        return { emit ->
            try {
                execute(request, tickets, emit)
            } finally {
                busy.set(false)
            }
        }
    }

    fun request(config: RunConfig, text: String) = ChatRequest(
        model = config.model,
        messages = task.messages(config.system, config.fewShot, text),
        format = if (config.schema) task.schema else null,
        options = config.options.toMap(),
        think = config.think,
    )

    fun attempt(ticket: Ticket, repeat: Int, reply: ChatReply): Attempt {
        val verdict = scorer.score(reply.text, ticket.expected)
        val r = reply.response
        return Attempt(
            ticketId = ticket.id,
            repeat = repeat,
            output = reply.text,
            valid = verdict.valid,
            error = verdict.error,
            fields = verdict.fields,
            correct = verdict.correct,
            wallMs = reply.wallMs,
            promptTokens = r.promptEvalCount,
            cachedTokens = r.promptEvalCachedCount,
            promptMs = r.promptEvalDuration?.let { it / NANOS_IN_MS },
            answerTokens = r.evalCount,
            generationMs = r.evalDuration?.let { it / NANOS_IN_MS },
            doneReason = r.doneReason,
        )
    }

    private fun execute(request: RunRequest, tickets: List<Ticket>, emit: Emit) {
        val config = request.config
        val name = request.name.trim().ifEmpty { config.model }
        emit(mapOf("type" to "start", "name" to name, "total" to tickets.size * request.repeats))
        try {
            val installed = ollama.installed().firstOrNull { it.name == config.model }
                ?: throw OllamaException("Модели ${config.model} нет в Ollama. Скачайте её: ollama pull ${config.model}")
            // Выгрузка перед загрузкой: время загрузки и память меряются одинаково для каждого прогона.
            ollama.unload(config.model)
            val loadMs = ollama.load(config.model, config.options.toMap())
            val loaded = ollama.running().firstOrNull { it.name == config.model }
            val resources = Resources(
                quantization = installed.details?.quantizationLevel,
                parameterSize = installed.details?.parameterSize,
                fileBytes = installed.size,
                loadMs = loadMs,
                memoryBytes = loaded?.size,
                contextLength = loaded?.contextLength,
            )
            emit(mapOf("type" to "loaded", "resources" to resources))

            val started = TimeSource.Monotonic.markNow()
            val attempts = mutableListOf<Attempt>()
            for (repeat in 1..request.repeats) {
                for (ticket in tickets) {
                    val attempt = attempt(ticket, repeat, ollama.chat(request(config, ticket.text)))
                    attempts += attempt
                    emit(mapOf("type" to "item", "attempt" to attempt))
                }
            }
            val experiment = Experiment(
                id = ID_FORMAT.format(Instant.now()),
                name = name,
                createdAt = Instant.now(),
                set = request.set,
                repeats = request.repeats,
                config = config,
                resources = resources,
                summary = summarize(attempts, request.repeats, started.elapsedNow().inWholeMilliseconds),
                attempts = attempts,
            )
            store.save(experiment)
            emit(mapOf("type" to "done", "experiment" to experiment))
        } catch (e: OllamaException) {
            emit(mapOf("type" to "error", "message" to e.message))
        }
    }

    private companion object {
        const val NANOS_IN_MS = 1_000_000
        val ID_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())
    }
}

package advent.llmservice.api

import advent.llmservice.LlmProperties
import advent.llmservice.limits.CLIENT
import advent.llmservice.limits.GenerationQueue
import advent.llmservice.limits.RateLimiter
import advent.llmservice.ollama.LocalModel
import advent.llmservice.ollama.OllamaChunk
import advent.llmservice.ollama.OllamaClient
import advent.llmservice.ollama.OllamaException
import advent.llmservice.ollama.OllamaMessage
import advent.llmservice.status.RequestJournal
import advent.llmservice.status.RequestRecord
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlin.time.TimeMark
import kotlin.time.TimeSource

// Атрибуты запроса, по которым обработчик ошибок пишет отказ в журнал.
const val RECEIVED = "received"
const val STREAM = "stream"
const val QUEUE_MS = "queueMs"

/**
 * OpenAI-совместимый чат. Порядок проверок: ключ (интерцептор) → лимит запросов ключа → место в очереди →
 * окно контекста (его меряет Ollama своим токенизатором) → генерация. Всё, что отказано до генерации,
 * уходит обычным HTTP-статусом, даже при `stream: true`: первые байты стрима пишутся, только когда модель
 * уже приняла запрос.
 */
@RestController
class ChatCompletionsController(
    private val props: LlmProperties,
    private val model: LocalModel,
    private val rateLimiter: RateLimiter,
    private val queue: GenerationQueue,
    private val journal: RequestJournal,
    private val json: JsonMapper,
) {

    @GetMapping("/v1/models")
    fun models() = ModelList(listOf(ModelCard(model.name, props.contextTokens)))

    @PostMapping("/v1/chat/completions")
    fun complete(
        @RequestBody body: ChatCompletionRequest,
        @RequestAttribute(CLIENT) client: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val received = TimeSource.Monotonic.markNow()
        request.setAttribute(RECEIVED, received)
        request.setAttribute(STREAM, body.stream)
        rateLimiter.take(client).headers().forEach(response::setHeader)
        if (body.messages.isEmpty()) throw ApiException.invalidRequest("messages пуст: нужна хотя бы одна реплика")
        val maxTokens = minOf(body.maxCompletionTokens ?: body.maxTokens ?: props.maxOutputTokens, props.maxOutputTokens)

        queue.enter().use {
            val call = Call(client, body.stream, received, received.elapsedNow().inWholeMilliseconds)
            request.setAttribute(QUEUE_MS, call.queueMs)
            val messages = body.messages.map { OllamaMessage(it.role, it.content) }
            model.generate(messages, maxTokens, body.temperature).use { generation ->
                if (body.stream) stream(generation, call, response) else whole(generation, call, response)
            }
        }
    }

    private fun whole(generation: OllamaClient.Generation, call: Call, response: HttpServletResponse) {
        val text = StringBuilder()
        val done = generation.collect { call.token(); text.append(it) }
        val completion = ChatCompletion(
            call.id, call.created, model.name,
            listOf(Choice(0, ChatMessage("assistant", text.toString()), finishReason(done))),
            call.usage(done), call.timings(done),
        )
        journal.record(call.record(200, null, done))
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.outputStream.write(json.writeValueAsBytes(completion))
    }

    /** SSE как у OpenAI: кусок с ролью, куски текста, последний — с finish_reason, usage и timings, затем [DONE]. */
    private fun stream(generation: OllamaClient.Generation, call: Call, response: HttpServletResponse) {
        response.contentType = "text/event-stream;charset=UTF-8"
        response.setHeader("Cache-Control", "no-cache")
        val out = response.outputStream
        fun send(data: String) {
            out.write("data: $data\n\n".toByteArray())
            out.flush()
        }
        fun chunk(delta: Delta, done: OllamaChunk? = null) = json.writeValueAsString(
            ChatCompletionChunk(
                call.id, call.created, model.name,
                listOf(ChunkChoice(0, delta, done?.let(::finishReason))),
                done?.let(call::usage), done?.let(call::timings),
            )
        )

        try {
            send(chunk(Delta(role = "assistant", content = "")))
            val done = generation.collect { call.token(); send(chunk(Delta(content = it))) }
            send(chunk(Delta(), done))
            send("[DONE]")
            journal.record(call.record(200, null, done))
        } catch (e: IOException) {
            // Клиент закрыл соединение. Генерация обрывается закрытием стрима Ollama в complete().
            journal.record(call.record(499, "client_closed", null))
        } catch (e: OllamaException) {
            // Статус 200 уже ушёл — ошибку получает клиент последним событием стрима, как у OpenAI.
            runCatching { send(json.writeValueAsString(ErrorBody(ErrorDetail(e.message.orEmpty(), "server_error", "model_unavailable")))) }
            journal.record(call.record(502, "model_unavailable", null))
        }
    }

    private fun finishReason(done: OllamaChunk) = if (done.doneReason == "length") "length" else "stop"

    private inner class Call(val client: String, val stream: Boolean, val received: TimeMark, val queueMs: Long) {
        val id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)
        val created = Instant.now().epochSecond
        private var firstTokenMs: Long? = null

        fun token() {
            if (firstTokenMs == null) firstTokenMs = received.elapsedNow().inWholeMilliseconds
        }

        fun usage(done: OllamaChunk): Usage {
            val prompt = done.promptEvalCount ?: 0
            val completion = done.evalCount ?: 0
            return Usage(prompt, completion, prompt + completion)
        }

        fun timings(done: OllamaChunk) = Timings(
            queueMs = queueMs,
            firstTokenMs = firstTokenMs,
            promptMs = done.promptEvalDuration?.let { it / 1_000_000 },
            generationMs = done.evalDuration?.let { it / 1_000_000 },
            tokensPerSecond = tokensPerSecond(done),
            cachedPromptTokens = done.promptEvalCachedCount,
        )

        fun record(status: Int, code: String?, done: OllamaChunk?) = RequestRecord(
            at = Instant.now(),
            client = client,
            status = status,
            code = code,
            stream = stream,
            totalMs = received.elapsedNow().inWholeMilliseconds,
            queueMs = queueMs,
            firstTokenMs = firstTokenMs,
            promptTokens = done?.promptEvalCount,
            completionTokens = done?.evalCount,
            tokensPerSecond = done?.let(::tokensPerSecond),
            finishReason = done?.let(::finishReason),
        )

        // На одном токене скорость не определена: Ollama относит его ко времени промпта, eval_duration — микросекунды.
        private fun tokensPerSecond(done: OllamaChunk): Double? {
            val tokens = done.evalCount?.takeIf { it > 1 } ?: return null
            val nanos = done.evalDuration?.takeIf { it > 0 } ?: return null
            return Math.round(tokens * 1e10 / nanos) / 10.0
        }
    }
}

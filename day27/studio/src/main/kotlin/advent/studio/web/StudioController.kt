package advent.studio.web

import advent.localllm.ChatResult
import advent.localllm.Message
import advent.localllm.ModelStatus
import advent.localllm.OllamaClient
import advent.localllm.OllamaException
import advent.localllm.commit.CommitWriter
import advent.localllm.commit.DIFF_LIMIT
import advent.localllm.commit.StagedChanges
import advent.studio.tools.Check
import advent.studio.tools.TextTask
import advent.studio.tools.Tools
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
import tools.jackson.databind.json.JsonMapper

data class RunRequest(val tool: String, val option: String = "", val text: String)

private val NDJSON = MediaType.parseMediaType("application/x-ndjson")
private val TEXT_UTF8 = MediaType("text", "plain", Charsets.UTF_8)

/**
 * Ответ `/api/run` — NDJSON-стрим событий: `start` (модель и пометка), `delta` (кусок текста по мере генерации),
 * затем `done` (итоговый текст, метрики, проверка формата) или `error` (ответ Ollama как есть).
 */
@RestController
@RequestMapping("/api")
class StudioController(private val ollama: OllamaClient, private val tools: Tools, private val mapper: JsonMapper) {

    @GetMapping("/status")
    fun status(): ModelStatus = ollama.status()

    /**
     * Стрим через ResponseBodyEmitter: каждое событие уходит в браузер сразу. StreamingResponseBody не годится —
     * Spring 7 отдаёт в него NonFlushingOutputStream, и весь ответ приходил одним куском в конце генерации.
     */
    @PostMapping("/run")
    fun run(@RequestBody request: RunRequest): ResponseEntity<ResponseBodyEmitter> {
        require(request.text.isNotBlank()) { "Введите текст" }
        // Ошибки ввода ловим до начала стрима, чтобы они пришли обычным 400, а не строкой внутри ответа.
        val job = if (request.tool == "commit") {
            commit(request.text)
        } else {
            text(tools.prepare(request.tool, request.option, request.text), request.text)
        }
        val emitter = ResponseBodyEmitter()
        Thread.startVirtualThread {
            try {
                job(Events(emitter))
                emitter.complete()
            } catch (e: Exception) {
                // Сюда же попадает обрыв со стороны браузера («Остановить»): send падает, стрим из Ollama закрывается.
                emitter.completeWithError(e)
            }
        }
        return ResponseEntity.ok().contentType(NDJSON).body(emitter)
    }

    private fun text(task: TextTask, text: String): (Events) -> Unit = { events ->
        events.send("type" to "start", "model" to ollama.model, "note" to task.note)
        try {
            val messages = listOf(Message("system", task.system), Message("user", text))
            val result = ollama.chatStream(messages, task.temperature) { events.send("type" to "delta", "text" to it) }
            val output = task.cleanup(result.content)
            events.done(output, result, task.check?.invoke(output))
        } catch (e: OllamaException) {
            events.send("type" to "error", "message" to e.message)
        }
    }

    private fun commit(diff: String): (Events) -> Unit {
        val changes = StagedChanges.fromDiff(diff)
        require(changes.files.isNotEmpty()) { "Вставьте вывод git diff: в тексте нет заголовков «diff --git a/… b/…»" }
        val writer = CommitWriter(ollama, changes)
        val note = listOfNotNull(
            "${changes.files.size} ${if (changes.files.size == 1) "файл" else "файлов"}",
            changes.scope?.let { "scope $it" },
            "дифф ужат до ${DIFF_LIMIT / 1000} тыс. символов".takeIf { writer.diffTruncated },
        ).joinToString(" · ")
        return { events ->
            events.send("type" to "start", "model" to ollama.model, "note" to note)
            try {
                // Модель отвечает JSON по схеме, поэтому без стрима: показывать нечего, пока заголовок не собран кодом.
                val suggestion = writer.first()
                events.done(suggestion.message, suggestion.result, null)
            } catch (e: OllamaException) {
                events.send("type" to "error", "message" to e.message)
            }
        }
    }

    private inner class Events(private val emitter: ResponseBodyEmitter) {
        fun send(vararg fields: Pair<String, Any?>) = emitter.send(mapper.writeValueAsString(mapOf(*fields)) + "\n", TEXT_UTF8)

        fun done(text: String, result: ChatResult, check: Check?) = send(
            "type" to "done",
            "text" to text,
            "check" to check?.let { mapOf("ok" to it.ok, "label" to it.label) },
            "metrics" to mapOf(
                "model" to result.model,
                "promptTokens" to result.promptTokens,
                "answerTokens" to result.answerTokens,
                "tokensPerSecond" to result.tokensPerSecond,
                "loadSeconds" to result.loadSeconds,
                "totalSeconds" to result.totalSeconds,
            ),
            "request" to result.rawRequest,
        )
    }
}

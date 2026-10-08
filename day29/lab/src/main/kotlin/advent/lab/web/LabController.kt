package advent.lab.web

import advent.lab.experiment.Attempt
import advent.lab.experiment.BuildRequest
import advent.lab.experiment.BuildResult
import advent.lab.experiment.Experiment
import advent.lab.experiment.ExperimentHead
import advent.lab.experiment.ExperimentRunner
import advent.lab.experiment.ExperimentStore
import advent.lab.experiment.ModelBuilder
import advent.lab.experiment.Preset
import advent.lab.experiment.Presets
import advent.lab.experiment.RunConfig
import advent.lab.experiment.RunRequest
import advent.lab.ollama.OllamaClient
import advent.lab.task.Fields
import advent.lab.task.Shot
import advent.lab.task.Ticket
import advent.lab.task.TicketTask
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

data class LoadedModel(val name: String, val memoryBytes: Long, val contextLength: Int?)

data class StatusView(val version: String, val baseUrl: String, val loaded: List<LoadedModel>)

/** defaults — параметры из Modelfile модели: их Ollama берёт, если в запросе поле не задано. */
data class ModelView(
    val name: String, val sizeBytes: Long, val family: String?, val parameterSize: String?, val quantization: String?,
    val thinking: Boolean, val defaults: Map<String, String>,
)

data class TaskView(val tickets: List<Ticket>, val shots: List<Shot>, val presets: List<Preset>, val schema: JsonNode)

/** ticketId — обращение из набора: тогда ответ сверяется с разметкой. Без него — свой текст, только разбор JSON. */
data class TryRequest(val config: RunConfig, val text: String, val ticketId: String? = null)

data class TryResult(val checked: Boolean, val attempt: Attempt, val loadMs: Long, val request: String)

private val NDJSON = MediaType.parseMediaType("application/x-ndjson")
private val TEXT_UTF8 = MediaType("text", "plain", Charsets.UTF_8)
private val UNLABELED = Fields(null, null, null, null, null)

@RestController
@RequestMapping("/api")
class LabController(
    private val ollama: OllamaClient,
    private val task: TicketTask,
    private val presets: Presets,
    private val runner: ExperimentRunner,
    private val store: ExperimentStore,
    private val builder: ModelBuilder,
    private val mapper: JsonMapper,
) {

    @GetMapping("/status")
    fun status() = StatusView(ollama.version(), ollama.baseUrl,
        ollama.running().map { LoadedModel(it.name, it.size, it.contextLength) })

    // Только модели, которые генерируют текст: эмбеддинги вроде nomic-embed-text в списке не нужны.
    @GetMapping("/models")
    fun models(): List<ModelView> = ollama.installed().mapNotNull { m ->
        val show = ollama.show(m.name)
        if ("completion" !in show.capabilities) return@mapNotNull null
        ModelView(m.name, m.size, m.details?.family, m.details?.parameterSize, m.details?.quantizationLevel,
            "thinking" in show.capabilities, parameters(show.parameters))
    }.sortedBy { it.name }

    // /api/show отдаёт параметры текстом Modelfile: «temperature    1» по строке на параметр.
    private fun parameters(text: String?): Map<String, String> = text.orEmpty().lines()
        .map { it.trim().split(Regex("\\s+"), limit = 2) }
        .filter { it.size == 2 && it[0] != "stop" }
        .associate { it[0] to it[1] }

    @GetMapping("/task")
    fun task() = TaskView(task.tickets, task.shots, presets.all, task.schema)

    @PostMapping("/try")
    fun tryOne(@RequestBody request: TryRequest): TryResult {
        require(request.text.isNotBlank()) { "Введите текст обращения" }
        require(request.config.model.isNotBlank()) { "Выберите модель" }
        val ticket = request.ticketId?.let { task.ticket(it) }?.takeIf { it.text == request.text }
        val reply = ollama.chat(runner.request(request.config, request.text))
        val attempt = runner.attempt(ticket ?: Ticket("own", "own", request.text, UNLABELED), 1, reply)
        return TryResult(ticket != null, attempt, (reply.response.loadDuration ?: 0) / 1_000_000, reply.requestBody)
    }

    /**
     * Прогон по набору — NDJSON-стрим: каждое обращение уходит в браузер, как только модель на него ответила.
     * ResponseBodyEmitter, а не StreamingResponseBody: в Spring 7 у второго flush() ничего не делает.
     */
    @PostMapping("/run")
    fun run(@RequestBody request: RunRequest): ResponseEntity<ResponseBodyEmitter> {
        val job = runner.prepare(request)
        val emitter = ResponseBodyEmitter()
        Thread.startVirtualThread {
            try {
                job { event -> emitter.send(mapper.writeValueAsString(event) + "\n", TEXT_UTF8) }
                emitter.complete()
            } catch (e: Exception) {
                // Сюда же попадает «Остановить»: браузер обрывает запрос, send падает, прогон прекращается.
                emitter.completeWithError(e)
            }
        }
        return ResponseEntity.ok().contentType(NDJSON).body(emitter)
    }

    @GetMapping("/experiments")
    fun experiments(): List<ExperimentHead> = store.list()

    @GetMapping("/experiments/{id}")
    fun experiment(@PathVariable id: String): Experiment = store.get(id)

    @DeleteMapping("/experiments/{id}")
    fun deleteExperiment(@PathVariable id: String) = store.delete(id)

    @PostMapping("/build")
    fun build(@RequestBody request: BuildRequest): BuildResult = builder.build(request)
}

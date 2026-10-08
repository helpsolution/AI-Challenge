package advent.lab.experiment

import advent.lab.task.FieldCheck
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

/** Параметры генерации. null — не отправлять: Ollama возьмёт значение из модели (для llama3.2 temperature 0,8, окно 32k). */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Options(
    val temperature: Double? = null,
    @JsonProperty("top_p") val topP: Double? = null,
    @JsonProperty("num_ctx") val numCtx: Int? = null,
    @JsonProperty("num_predict") val numPredict: Int? = null,
) {
    fun toMap(): Map<String, Any>? = buildMap {
        temperature?.let { put("temperature", it) }
        topP?.let { put("top_p", it) }
        numCtx?.let { put("num_ctx", it) }
        numPredict?.let { put("num_predict", it) }
    }.ifEmpty { null }
}

/** Всё, что определяет ответ: модель, промпт-шаблон (системный промпт + примеры), JSON-схема и параметры. */
data class RunConfig(
    val model: String,
    val system: String = "",
    val fewShot: Boolean = false,
    val schema: Boolean = false,
    val options: Options = Options(),
    // null — не отправлять. Только для моделей, которые умеют рассуждать (qwen3.5): false выключает рассуждения.
    val think: Boolean? = null,
)

/** Одна попытка разобрать одно обращение. Время — в миллисекундах, из ответа Ollama, кроме wallMs. */
data class Attempt(
    val ticketId: String,
    val repeat: Int,
    val output: String,
    val valid: Boolean,
    val error: String?,
    val fields: Map<String, FieldCheck>,
    val correct: Int,
    val wallMs: Long,
    val promptTokens: Int?,
    val cachedTokens: Int?,
    val promptMs: Long?,
    val answerTokens: Int?,
    val generationMs: Long?,
    val doneReason: String?,
)

/** Чего стоит модель: файл на диске, память после загрузки с этим окном контекста, время загрузки. */
data class Resources(
    val quantization: String?,
    val parameterSize: String?,
    val fileBytes: Long?,
    val loadMs: Long,
    val memoryBytes: Long?,
    val contextLength: Int?,
)

data class Summary(
    val attempts: Int,
    val fieldsCorrect: Int,
    val fieldsTotal: Int,
    val perField: Map<String, Int>,
    /** Все пять полей верны. */
    val exact: Int,
    val validJson: Int,
    /** Обращения, на которых все повторы дали одинаковый ответ. null при одном повторе. */
    val stable: Int?,
    val tickets: Int,
    val latencyMedianMs: Long,
    val latencyP90Ms: Long,
    val generationTps: Double?,
    val promptTps: Double?,
    val promptTokens: Int?,
    val cachedTokens: Int?,
    val answerTokens: Int?,
    /** Ответ упёрся в num_predict и оборван. */
    val truncated: Int,
    val totalMs: Long,
)

data class Experiment(
    val id: String,
    val name: String,
    val createdAt: Instant,
    val set: String,
    val repeats: Int,
    val config: RunConfig,
    val resources: Resources,
    val summary: Summary,
    val attempts: List<Attempt>,
)

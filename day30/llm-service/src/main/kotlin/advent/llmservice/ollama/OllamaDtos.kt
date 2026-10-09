package advent.llmservice.ollama

import com.fasterxml.jackson.annotation.JsonProperty

data class OllamaMessage(val role: String, val content: String)

data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaMessage>,
    val options: Map<String, Any>,
    // У qwen3 рассуждения по умолчанию включены: сотни токенов «размышлений» до ответа на слабом процессоре.
    val think: Boolean = false,
    val stream: Boolean = true,
    // Запрос длиннее окна — ошибка 400. По умолчанию Ollama молча отрезает начало диалога.
    val truncate: Boolean = false,
    // Ответ, упёршийся в окно, останавливается с done_reason: length, а не сдвигает окно, забывая начало.
    val shift: Boolean = false,
)

/** Строка NDJSON-стрима /api/chat. Метрики приходят только в последней, с `done: true`. Длительности — в наносекундах. */
data class OllamaChunk(
    val message: OllamaMessage? = null,
    val done: Boolean = false,
    val error: String? = null,
    @JsonProperty("done_reason") val doneReason: String? = null,
    @JsonProperty("prompt_eval_count") val promptEvalCount: Int? = null,
    @JsonProperty("prompt_eval_cached_count") val promptEvalCachedCount: Int? = null,
    @JsonProperty("prompt_eval_duration") val promptEvalDuration: Long? = null,
    @JsonProperty("eval_count") val evalCount: Int? = null,
    @JsonProperty("eval_duration") val evalDuration: Long? = null,
    @JsonProperty("load_duration") val loadDuration: Long? = null,
)

data class RunningModels(val models: List<RunningModel> = emptyList())

data class RunningModel(
    val name: String,
    val size: Long = 0,
    @JsonProperty("context_length") val contextLength: Int? = null,
)

data class OllamaVersion(val version: String = "")

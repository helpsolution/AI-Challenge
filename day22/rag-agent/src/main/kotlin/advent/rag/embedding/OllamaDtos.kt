package advent.rag.embedding

import com.fasterxml.jackson.annotation.JsonProperty

/** Тело POST /api/embed у Ollama. Наружу из пакета не выходит: это формат провайдера, а не контракт кубика. */
internal data class OllamaEmbedRequest(
    val model: String,
    val input: List<String>,
    val truncate: Boolean,
)

internal data class OllamaEmbedResponse(
    val embeddings: List<FloatArray>,
    @JsonProperty("prompt_eval_count") val promptEvalCount: Int?,
)

/** Ошибку Ollama отдаёт телом {"error": "..."}. */
internal data class OllamaError(val error: String)

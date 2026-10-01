package advent.rag.embedding

import com.fasterxml.jackson.annotation.JsonProperty

internal data class OllamaEmbedRequest(
    val model: String,
    val input: List<String>,
    val truncate: Boolean,
)

internal data class OllamaEmbedResponse(
    val embeddings: List<FloatArray>,
    @JsonProperty("prompt_eval_count") val promptEvalCount: Int?,
)

internal data class OllamaError(val error: String)

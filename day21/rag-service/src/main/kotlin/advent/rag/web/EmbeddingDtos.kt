package advent.rag.web

import advent.rag.embedding.Embeddings
import io.swagger.v3.oas.annotations.media.Schema
import kotlin.math.sqrt

/** Тело POST /api/embeddings. */
data class EmbeddingRequest(
    @field:Schema(
        description = "Тексты, для которых нужны векторы.",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val texts: List<String>,
)

/** Ответ POST /api/embeddings. */
data class EmbeddingResponse(
    val model: String,
    @field:Schema(description = "Длина каждого вектора. У nomic-embed-text — 768.")
    val dimensions: Int,
    @field:Schema(description = "Сколько токенов модель прочитала суммарно по всем текстам.")
    val tokens: Int?,
    @field:Schema(description = "Время запроса к Ollama, мс. Первый вызов после простоя дольше: модель грузится в память.")
    val durationMs: Long,
    val items: List<EmbeddingItem>,
) {
    companion object {
        fun of(texts: List<String>, result: Embeddings) = EmbeddingResponse(
            model = result.model,
            dimensions = result.vectors.first().size,
            tokens = result.tokens,
            durationMs = result.duration.inWholeMilliseconds,
            items = texts.zip(result.vectors).mapIndexed { i, (text, vector) ->
                EmbeddingItem(index = i, text = text, norm = vector.norm(), vector = vector.asList())
            },
        )
    }
}

data class EmbeddingItem(
    @field:Schema(description = "Номер текста во входном списке.")
    val index: Int,
    val text: String,
    @field:Schema(description = "Длина вектора (L2-норма). Около 1.0: Ollama отдаёт векторы уже нормированными.")
    val norm: Double,
    val vector: List<Float>,
)

private fun FloatArray.norm(): Double = sqrt(sumOf { it.toDouble() * it })

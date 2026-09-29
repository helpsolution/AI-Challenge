package advent.rag.embedding

import kotlin.time.Duration

/** Ответ кубика: по вектору на каждый входной текст, в том же порядке. */
data class Embeddings(
    val model: String,
    val vectors: List<FloatArray>,
    /** Сколько токенов модель прочитала суммарно по всем текстам; null, если Ollama не сообщила. */
    val tokens: Int?,
    /** Полное время запроса к Ollama, вместе с сетью. */
    val duration: Duration,
)

/** Ollama недоступна или ответила не так, как ожидалось: виноват не вход, а внешний сервис. */
class EmbeddingException(message: String) : RuntimeException(message)

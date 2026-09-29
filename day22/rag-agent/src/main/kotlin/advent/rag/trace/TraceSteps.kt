package advent.rag.trace

import advent.rag.embedding.QueryEmbedding
import advent.rag.http.HttpExchange
import advent.rag.llm.ChatMessage
import advent.rag.llm.Completion
import advent.rag.retrieval.Retrieval
import kotlin.math.sqrt
import kotlin.time.Duration

/** Шаг конвейера агента. У каждого шага своя модель: у эмбеддинга — вектор, у поиска — рейтинг, у LLM — HTTP-обмен. */
sealed interface TraceStep {
    val kind: String
    val durationMs: Double
}

/** Вопрос → вектор: что ушло в Ollama и что вернулось. */
data class EmbeddingStep(
    val model: String,
    /** Текст вопроса вместе с префиксом задачи — ровно то, что закодировала модель. */
    val input: String,
    val dimensions: Int,
    val tokens: Int?,
    /** Длина вектора. Около 1.0: Ollama отдаёт векторы уже нормированными. */
    val norm: Double,
    val vector: FloatArray,
    val exchange: HttpExchange,
) : TraceStep {
    override val kind = "embedding"
    override val durationMs = exchange.durationMs.toDouble()

    companion object {
        fun of(model: String, query: QueryEmbedding) = EmbeddingStep(
            model = model,
            input = query.input,
            dimensions = query.vector.size,
            tokens = query.tokens,
            norm = sqrt(query.vector.sumOf { it.toDouble() * it }),
            vector = query.vector,
            exchange = query.exchange,
        )
    }
}

/** Вектор вопроса сравнивается с каждым чанком базы; первые [topK] мест уходят в промпт. */
data class SearchStep(
    val chunksCompared: Int,
    val topK: Int,
    /** Косинус каждого чанка базы к вопросу, по убыванию: по ним видно, насколько выделяются лучшие. */
    val scores: List<Double>,
    /** Начало рейтинга: top-K, ушедшие в промпт, и следующие за ними, которые не попали. */
    val candidates: List<Candidate>,
    override val durationMs: Double,
) : TraceStep {
    override val kind = "search"

    companion object {
        /** Сколько мест после top-K показать: «почти попали» объясняет промахи поиска. */
        private const val RUNNERS_UP = 5

        fun of(retrieval: Retrieval) = SearchStep(
            chunksCompared = retrieval.ranking.size,
            topK = retrieval.topK,
            scores = retrieval.ranking.map { it.score },
            candidates = retrieval.ranking.take(retrieval.topK + RUNNERS_UP).mapIndexed { i, found ->
                Candidate(
                    rank = i + 1,
                    selected = i < retrieval.topK,
                    chunkId = found.chunk.chunkId,
                    title = found.chunk.title,
                    score = found.score,
                    text = found.chunk.text,
                )
            },
            durationMs = retrieval.searchDuration.toMillis(),
        )
    }
}

/** Место чанка в рейтинге. Для выбранных [rank] совпадает с номером фрагмента в промпте. */
data class Candidate(
    val rank: Int,
    val selected: Boolean,
    val chunkId: String,
    val title: String,
    val score: Double,
    val text: String,
)

/** Вопрос и фрагменты склеиваются в сообщения для модели. */
data class PromptStep(
    val messages: List<ChatMessage>,
    /** Из чего сложен промпт, в символах: правила, фрагменты базы, сам вопрос. */
    val parts: List<PromptPart>,
    override val durationMs: Double,
) : TraceStep {
    override val kind = "prompt"

    companion object {
        fun of(messages: List<ChatMessage>, question: String, duration: Duration): PromptStep {
            val rules = messages.filter { it.role == "system" }.sumOf { it.content.length }
            val user = messages.filter { it.role == "user" }.sumOf { it.content.length }
            val context = user - question.length
            return PromptStep(
                messages = messages,
                parts = listOfNotNull(
                    PromptPart("Правила", rules),
                    if (context > 0) PromptPart("Фрагменты базы", context) else null,
                    PromptPart("Вопрос", question.length),
                ),
                durationMs = duration.toMillis(),
            )
        }
    }
}

data class PromptPart(val label: String, val chars: Int)

/** Промпт → ответ: настоящий HTTP-обмен с DeepSeek. */
data class LlmStep(
    val model: String,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val finishReason: String?,
    val exchange: HttpExchange,
) : TraceStep {
    override val kind = "llm"
    override val durationMs = exchange.durationMs.toDouble()

    companion object {
        fun of(completion: Completion) = LlmStep(
            model = completion.model,
            promptTokens = completion.promptTokens,
            completionTokens = completion.completionTokens,
            finishReason = completion.finishReason,
            exchange = completion.exchange,
        )
    }
}

/** Миллисекунды с долями: поиск по памяти и склейка промпта занимают меньше миллисекунды. */
private fun Duration.toMillis(): Double = inWholeMicroseconds / 1000.0

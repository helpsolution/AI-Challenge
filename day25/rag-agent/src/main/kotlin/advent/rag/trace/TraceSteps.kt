package advent.rag.trace

import advent.rag.embedding.QueryEmbedding
import advent.rag.http.HttpExchange
import advent.rag.llm.ChatMessage
import advent.rag.llm.Completion
import advent.rag.memory.MemoryResult
import advent.rag.memory.MemoryUpdate
import advent.rag.memory.TaskState
import advent.rag.retrieval.Retrieval
import advent.rag.retrieval.Search
import kotlin.math.sqrt
import kotlin.time.Duration

sealed interface TraceStep {
    val kind: String
    val durationMs: Double
}

data class MemoryStep(
    val previous: TaskState,
    val state: TaskState,
    val update: MemoryUpdate,
    val history: List<ChatMessage>,
    val exchange: HttpExchange?,
) : TraceStep {
    override val kind = "memory"
    override val durationMs = update.durationMs.toDouble()

    companion object {
        fun of(result: MemoryResult, history: List<ChatMessage>) =
            MemoryStep(result.previous, result.state, result.update, history, result.completion?.exchange)
    }
}

data class EmbeddingStep(
    val model: String,
    val input: String,
    val dimensions: Int,
    val tokens: Int?,
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

data class SearchStep(
    val chunksCompared: Int,
    val topK: Int,
    val scores: List<Double>,
    val candidates: List<Candidate>,
    override val durationMs: Double,
) : TraceStep {
    override val kind = "search"

    companion object {
        private const val RUNNERS_UP = 5

        fun of(search: Search) = SearchStep(
            chunksCompared = search.ranking.size,
            topK = search.candidateK,
            scores = search.ranking.map { it.score },
            candidates = search.ranking.take(search.candidateK + RUNNERS_UP).mapIndexed { i, found ->
                Candidate(
                    rank = i + 1,
                    selected = i < search.candidateK,
                    chunkId = found.chunk.chunkId,
                    title = found.chunk.title,
                    score = found.score,
                    text = found.chunk.content,
                )
            },
            durationMs = search.duration.toMillis(),
        )
    }
}

data class Candidate(
    val rank: Int,
    val selected: Boolean,
    val chunkId: String,
    val title: String,
    val score: Double,
    val text: String,
)

data class RerankStep(
    val model: String,
    val candidateK: Int,
    val topK: Int,
    val candidates: List<RerankCandidate>,
    val exchange: HttpExchange,
) : TraceStep {
    override val kind = "rerank"
    override val durationMs = exchange.durationMs.toDouble()

    companion object {
        fun of(retrieval: Retrieval): RerankStep {
            val search = retrieval.search
            val before = search.ranking.mapIndexed { i, found -> found.chunk.chunkId to i + 1 }.toMap()
            return RerankStep(retrieval.reranking.model, search.candidateK, search.topK,
                retrieval.reranking.ranking.mapIndexed { i, found ->
                    RerankCandidate(i + 1, before.getValue(found.chunk.chunkId), i < search.topK,
                        found.chunk.chunkId, found.chunk.title, found.chunk.section, found.score, found.rerankScore, found.chunk.content)
                }, retrieval.reranking.exchange)
        }
    }
}

data class RerankCandidate(
    val rank: Int, val originalRank: Int, val selected: Boolean, val chunkId: String,
    val title: String, val section: String?, val score: Double, val rerankScore: Double?, val text: String,
)

data class PromptStep(
    val messages: List<ChatMessage>,
    val parts: List<PromptPart>,
    override val durationMs: Double,
) : TraceStep {
    override val kind = "prompt"

    companion object {
        fun of(messages: List<ChatMessage>, question: String, memoryBlock: String?, duration: Duration): PromptStep {
            val system = messages.filter { it.role == "system" }.sumOf { it.content.length }
            val memory = memoryBlock?.length ?: 0
            val user = messages.filter { it.role != "system" }.sumOf { it.content.length }
            val context = user - question.length
            return PromptStep(
                messages = messages,
                parts = listOfNotNull(
                    PromptPart("Правила", system - memory),
                    if (memory > 0) PromptPart("Память задачи", memory) else null,
                    if (context > 0) PromptPart("Фрагменты и история", context) else null,
                    PromptPart("Вопрос", question.length),
                ),
                durationMs = duration.toMillis(),
            )
        }
    }
}

data class PromptPart(val label: String, val chars: Int)

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

private fun Duration.toMillis(): Double = inWholeMicroseconds / 1000.0

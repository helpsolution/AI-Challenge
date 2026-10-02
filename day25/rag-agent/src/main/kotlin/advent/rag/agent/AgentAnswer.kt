package advent.rag.agent

import advent.rag.knowledge.ScoredChunk
import advent.rag.llm.ChatMessage
import advent.rag.llm.Completion
import advent.rag.memory.MemoryUpdate
import advent.rag.memory.TaskState
import advent.rag.retrieval.Retrieval
import java.time.Instant

/** Один обмен диалога: реплика, ответ с источниками и память задачи после этого обмена. */
data class Turn(
    val number: Int,
    val at: Instant,
    val question: String,
    val answer: String,
    val sources: List<Source>,
    val memory: TaskState,
    val memoryUpdate: MemoryUpdate,
    val warnings: List<String>,
    val prompt: List<ChatMessage>,
    val llm: LlmCall,
    val retrievalMs: Long,
    val reranking: RerankInfo,
    val traceId: String,
)

data class RerankInfo(val model: String, val candidateK: Int, val topK: Int, val durationMs: Long) {
    companion object {
        fun of(retrieval: Retrieval) = RerankInfo(retrieval.reranking.model, retrieval.search.candidateK, retrieval.search.topK,
            retrieval.reranking.exchange.durationMs)
    }
}

data class Source(
    val number: Int, val chunkId: String, val source: String, val title: String,
    val url: String?, val section: String?, val score: Double, val text: String,
    // used означает наличие [n] в ответе, а не проверку утверждения по источнику.
    val used: Boolean,
    val rerankScore: Double? = null,
) {
    companion object {
        fun of(number: Int, found: ScoredChunk, used: Boolean) = Source(number, found.chunk.chunkId,
            found.chunk.source, found.chunk.title, found.chunk.url, found.chunk.section, found.score, found.chunk.content, used, found.rerankScore)
    }
}

data class LlmCall(val model: String, val promptTokens: Int?, val completionTokens: Int?, val durationMs: Long) {
    companion object {
        fun of(completion: Completion) = LlmCall(completion.model, completion.promptTokens, completion.completionTokens, completion.exchange.durationMs)
    }
}

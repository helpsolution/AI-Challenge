package advent.rag.agent

import advent.rag.chat.ChatProperties
import advent.rag.chat.ChatStore
import advent.rag.embedding.EmbeddingClient
import advent.rag.llm.ChatMessage
import advent.rag.llm.LlmClient
import advent.rag.memory.TaskMemory
import advent.rag.retrieval.Retriever
import advent.rag.trace.*
import org.springframework.stereotype.Component
import java.time.Instant
import kotlin.time.measureTimedValue

@Component
class Agent(
    private val llm: LlmClient, private val retriever: Retriever, private val embeddings: EmbeddingClient,
    private val prompts: PromptBuilder, private val journal: TraceJournal, private val memory: TaskMemory,
    private val chats: ChatStore, private val chat: ChatProperties,
) {
    /** Реплика → память задачи → поиск по реплике → ответ с источниками → сохранение обмена. */
    fun reply(conversationId: String, message: String): Turn {
        require(message.isNotBlank() && message.length <= 6000) { "Сообщение должно содержать от 1 до 6000 символов" }
        val conversation = chats.get(conversationId)
        val q = message.trim()
        val history = conversation.turns.takeLast(chat.historyTurns)
            .flatMap { listOf(ChatMessage("user", it.question), ChatMessage("assistant", it.answer)) }
        val trace = TraceRecorder(conversationId, q)
        try {
            val remembered = memory.update(conversation.memory, history, q)
            trace += MemoryStep.of(remembered, history)
            val search = retriever.search(q)
            trace += EmbeddingStep.of(embeddings.model, search.query)
            trace += SearchStep.of(search)
            val retrieval = retriever.rerank(q, search)
            trace += RerankStep.of(retrieval)
            val memoryBlock = if (remembered.update.enabled) prompts.memoryBlock(remembered.state) else null
            val (prompt, duration) = measureTimedValue {
                prompts.answer(q, history, remembered.state.takeIf { remembered.update.enabled }, retrieval.chunks)
            }
            trace += PromptStep.of(prompt, q, memoryBlock, duration)
            val completion = llm.complete(prompt)
            trace += LlmStep.of(completion)
            val cited = CITATIONS.findAll(completion.text).flatMap { it.groupValues[1].split(Regex("\\s*[,;]\\s*")).map(String::toIntOrNull).filterNotNull() }.toSet()
            val sources = retrieval.chunks.mapIndexed { i, found -> Source.of(i + 1, found, i + 1 in cited) }
            val warnings = buildList {
                if (remembered.update.status == "fallback") add(remembered.update.reason!!)
                if (cited.any { it !in 1..sources.size }) add("Модель указала номер источника, которого не было в контексте")
                if (cited.isEmpty()) add("Ответ не содержит ссылок на фрагменты; его привязка к источникам не подтверждена")
                if (completion.finishReason == "length") add("Ответ достиг лимита токенов и может быть обрезан")
            }
            val turn = Turn(conversation.turns.size + 1, Instant.now(), q, completion.text, sources, remembered.state,
                remembered.update, warnings, prompt, LlmCall.of(completion), retrieval.durationMs, RerankInfo.of(retrieval), trace.id)
            chats.append(conversationId, turn)
            journal.save(trace.answered(turn.answer))
            return turn
        } catch (e: RuntimeException) { journal.save(trace.failed(e.message ?: "Ошибка агента")); throw e }
    }

    companion object {
        private val CITATIONS = Regex("\\[(\\d+(?:\\s*[,;]\\s*\\d+)*)]")
    }
}

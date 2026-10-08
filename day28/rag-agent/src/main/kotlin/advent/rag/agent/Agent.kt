package advent.rag.agent

import advent.rag.embedding.EmbeddingClient
import advent.rag.grounding.GroundingChecker
import advent.rag.llm.ChatMessage
import advent.rag.llm.ChatModel
import advent.rag.llm.Models
import advent.rag.reranking.RerankedChunk
import advent.rag.retrieval.Retriever
import advent.rag.rewrite.QueryRewriter
import advent.rag.trace.*
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.measureTimedValue

// События одного вопроса в порядке появления: поиск, затем по каждой модели «начала» и «ответила».
interface AgentListener {
    fun retrieved(result: RetrievalResult)
    fun started(model: ModelRef)
    fun answered(answer: ModelAnswer)
}

@Component
class Agent(
    private val models: Models, private val retriever: Retriever, private val embeddings: EmbeddingClient,
    private val prompts: PromptBuilder, private val grounding: GroundingChecker, private val journal: TraceJournal,
    private val rewriter: QueryRewriter,
) {
    fun validate(question: String, history: List<ChatMessage>) {
        require(question.isNotBlank() && question.length <= 6000) { "Вопрос должен содержать от 1 до 6000 символов" }
        require(history.size <= 12 && history.sumOf { it.content.length } <= 30000 &&
            history.all { it.role in listOf("user", "assistant") && it.content.isNotBlank() && it.content.length <= 10000 }) { "Некорректная история диалога" }
    }

    // Облако отвечает параллельно с локальными моделями. Локальные идут по очереди: они делят один GPU
    // и, запущенные вместе, замедлили бы друг друга — замер скорости стал бы нечестным.
    fun ask(question: String, history: List<ChatMessage>, listener: AgentListener) {
        validate(question, history)
        val q = question.trim()
        val trace = TraceRecorder(q)
        try {
            val rewritten = rewriter.rewrite(q, history)
            trace += RewriteStep(rewritten.decision, models.main.model, rewritten.completion?.exchange)
            val retrieval = retriever.search(rewritten.decision.query)
            trace += EmbeddingStep.of(embeddings.model, retrieval.query)
            trace += SearchStep.of(retrieval)
            trace += RerankStep.of(retrieval)
            trace += ThresholdStep.of(retrieval)
            val context = retrieval.context
            val relevance = Relevance.of(retrieval)
            val prompt = if (context.isEmpty()) emptyList() else {
                val (prompt, duration) = measureTimedValue { prompts.answer(q, history, context) }
                trace += PromptStep.of(prompt, q, duration)
                prompt
            }
            listener.retrieved(RetrievalResult(q, rewritten.decision, relevance,
                context.mapIndexed { i, found -> Source.of(i + 1, found) }, prompt,
                retrieval.durationMs + rewritten.decision.durationMs, models.all.map(ModelRef::of),
                listOfNotNull(rewritten.decision.takeIf { it.status == "fallback" }?.let { it.reason + "; использована прежняя подготовка запроса" }),
                trace.id))

            val answers = ConcurrentHashMap<String, ModelAnswer>()
            fun answer(model: ChatModel) {
                listener.started(ModelRef.of(model))
                val result = if (prompt.isEmpty()) weak(model, relevance) else generate(model, prompt, context, trace)
                answers[model.model] = result
                listener.answered(result)
            }
            // Если браузер ушёл, отправка события падает; облачный поток просто завершается, локальный цикл прервётся сам.
            val cloud = models.cloud?.let { Thread.startVirtualThread { runCatching { answer(it) } } }
            models.local.forEach(::answer)
            cloud?.join()

            val main = answers.getValue(models.main.model)
            journal.save(trace.answered(main.status.code, main.answer))
        } catch (e: RuntimeException) { journal.save(trace.failed(e.message ?: "Ошибка агента")); throw e }
    }

    private fun generate(model: ChatModel, prompt: List<ChatMessage>, context: List<RerankedChunk>, trace: TraceRecorder): ModelAnswer {
        val completion = try {
            model.complete(prompt, prompts.answerSchema, maxTokens = 3000)
        } catch (e: RuntimeException) {
            val error = e.message ?: e.javaClass.simpleName
            trace += LlmFailureStep(model.provider, model.model, error)
            return ModelAnswer(model.provider, model.model, AnswerStatus.FAILED, "", error, emptyList(), emptyList(), null, null)
        }
        trace += LlmStep.of(model.provider, model.model, completion)
        val (checked, checkDuration) = measureTimedValue { grounding.check(completion, context) }
        trace += GroundingStep.of(model.model, checked, checkDuration)
        val llm = LlmCall.of(completion)
        return when (checked.status) {
            AnswerStatus.ANSWERED -> {
                val numbers = checked.quotes.map { it.number }.distinct().sorted()
                ModelAnswer(model.provider, model.model, AnswerStatus.ANSWERED, checked.answer!!,
                    "Цитаты найдены дословно во фрагментах " + numbers.joinToString(" ") { "[$it]" },
                    numbers.map { Source.of(it, context[it - 1]) }, checked.quotes, llm, null)
            }
            AnswerStatus.NO_ANSWER -> ModelAnswer(model.provider, model.model, AnswerStatus.NO_ANSWER, UNKNOWN,
                "Модель не нашла ответа среди фрагментов, прошедших порог: ${context.size}", emptyList(), emptyList(), llm, null)
            else -> ModelAnswer(model.provider, model.model, AnswerStatus.UNVERIFIED, UNKNOWN,
                "Ответ модели отклонён проверкой цитат: " + checked.errors.joinToString("; "), emptyList(), emptyList(), llm,
                checked.answer)
        }
    }

    private fun weak(model: ChatModel, relevance: Relevance) = ModelAnswer(model.provider, model.model, AnswerStatus.WEAK_CONTEXT,
        UNKNOWN, "Ни один фрагмент не прошёл порог релевантности: лучшая оценка BGE %.2f ниже %.2f. Модель не вызывалась"
            .format(relevance.bestScore, relevance.minScore), emptyList(), emptyList(), null, null)

    companion object {
        const val UNKNOWN = "Не знаю. Уточните вопрос."
    }
}

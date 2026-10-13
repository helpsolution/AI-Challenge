package advent.review.review

import advent.review.diff.ChangeType
import advent.review.diff.DiffParser
import advent.review.diff.FileDiff
import advent.review.llm.ChatMessage
import advent.review.llm.DeepSeekClient
import advent.review.llm.LlmException
import advent.review.rag.CodeFragment
import advent.review.rag.ContextRetriever
import advent.review.rag.Corpus
import advent.review.rag.DocFragment
import advent.review.rag.Retrieval
import advent.review.rag.RuleFragment
import advent.review.rag.UsageFragment
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import kotlin.time.TimeSource

/** Цепочка ревью: diff → контекст RAG → DeepSeek → проверка замечаний. */
@Component
class ReviewPipeline(
    private val properties: ReviewProperties,
    private val corpus: Corpus,
    private val retriever: ContextRetriever,
    private val prompts: PromptBuilder,
    private val llm: DeepSeekClient,
    private val validator: FindingValidator,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private class Spent(val model: String) {
        var attempts = 0
        var promptTokens = 0
        var completionTokens = 0
    }

    fun review(diffText: String): ReviewResult {
        val started = TimeSource.Monotonic.markNow()
        val stages = mutableListOf<Stage>()
        val warnings = mutableListOf<String>()

        var mark = TimeSource.Monotonic.markNow()
        val files = DiffParser.parse(diffText).map { changed(it, warnings) }
        stages += Stage("📥 Diff", mark.elapsedNow().inWholeMilliseconds,
            "файлов: ${files.size}, +${files.sumOf { it.diff.added }} −${files.sumOf { it.diff.removed }}")
        log.info("Diff: файлов {}", files.size)
        if (files.none { !it.diff.binary && it.diff.hunks.isNotEmpty() }) {
            return ReviewResult(files, emptyList(), emptyList(), "В PR нет текстовых изменений — ревьюить нечего.", emptyList(),
                emptyList(), stages, null, warnings, null, started.elapsedNow().inWholeMilliseconds)
        }

        mark = TimeSource.Monotonic.markNow()
        val retrieval = retriever.retrieve(files)
        retrieval.warning?.let(warnings::add)
        stages += Stage("📚 RAG", mark.elapsedNow().inWholeMilliseconds, describe(retrieval))
        log.info("RAG: {}", describe(retrieval))

        mark = TimeSource.Monotonic.markNow()
        val spent = Spent(llm.model)
        val validated = try {
            ask(prompts.build(files, retrieval.fragments), files, retrieval.fragments.size, spent)
        } catch (e: LlmException) {
            stages += Stage("🧠 LLM", mark.elapsedNow().inWholeMilliseconds, "ошибка после попыток: ${spent.attempts}")
            return ReviewResult(files, retrieval.scopes, retrieval.fragments, null, emptyList(), emptyList(), stages,
                usage(spent), warnings, e.message, started.elapsedNow().inWholeMilliseconds)
        }
        stages += Stage("🧠 LLM", mark.elapsedNow().inWholeMilliseconds,
            "${spent.model}, попыток: ${spent.attempts}, токенов: ${spent.promptTokens} → ${spent.completionTokens}")
        val total = validated.accepted.size + validated.rejected.size
        stages += Stage("✅ Проверка замечаний", 0, "принято ${validated.accepted.size} из $total")
        log.info("Замечаний: принято {} из {}", validated.accepted.size, total)

        val findings = validated.accepted.sortedWith(compareBy({ it.category }, { it.severity }, { it.file }, { it.line }))
        return ReviewResult(files, retrieval.scopes, retrieval.fragments, validated.summary.ifEmpty { null }, findings,
            validated.rejected, stages, usage(spent), warnings, null, started.elapsedNow().inWholeMilliseconds)
    }

    // Retry по лекции: сетевые сбои и 429/5xx — с паузой, невалидный ответ — с уточнением, что не так.
    private fun ask(prompt: List<ChatMessage>, files: List<ChangedFile>, fragments: Int, spent: Spent): Validated {
        var messages = prompt
        var lastError = ""
        repeat(properties.llmAttempts) { index ->
            spent.attempts = index + 1
            try {
                val completion = llm.complete(messages)
                spent.promptTokens += completion.promptTokens
                spent.completionTokens += completion.completionTokens
                if (completion.finishReason == "length") throw InvalidOutput("ответ обрезан лимитом max_tokens, сократи details и suggestion")
                return validator.validate(completion.text, files, fragments)
            } catch (e: InvalidOutput) {
                lastError = "ответ модели отклонён: ${e.message}"
                messages = prompt + ChatMessage("user", "Предыдущий ответ отклонён: ${e.message}. Верни только JSON по схеме из инструкции.")
            } catch (e: LlmException) {
                if (!e.retryable) throw e
                lastError = e.message.orEmpty()
                Thread.sleep(RETRY_PAUSE_MS * (index + 1))
            }
            log.warn("Попытка {} из {}: {}", index + 1, properties.llmAttempts, lastError)
        }
        throw LlmException("Нет ответа после ${properties.llmAttempts} попыток. Последняя ошибка: $lastError", retryable = false)
    }

    // Новая версия файла нужна целиком, чтобы модель видела окружение изменений.
    // Если файл на диске не совпадает с diff, номера строк в листинге соврут — тогда только участки diff.
    private fun changed(diff: FileDiff, warnings: MutableList<String>): ChangedFile {
        if (diff.binary || diff.type == ChangeType.DELETED) return ChangedFile(diff, null)
        val text = corpus.read(diff.path)
        if (text == null) {
            warnings += "Файл ${diff.path} не прочитан, модель видит только участки diff"
            return ChangedFile(diff, null)
        }
        val lines = text.removeSuffix("\n").split('\n').map { it.removeSuffix("\r") }
        if (diff.visibleLines.any { (number, line) -> lines.getOrNull(number - 1) != line.text }) {
            warnings += "Файл ${diff.path} на диске не совпадает с diff, модель видит только участки diff"
            return ChangedFile(diff, null)
        }
        return ChangedFile(diff, lines.takeIf { it.size <= properties.fullFileLines })
    }

    private fun describe(retrieval: Retrieval): String {
        val counts = retrieval.fragments.groupingBy {
            when (it) {
                is RuleFragment -> "правила"
                is DocFragment -> "документация"
                is UsageFragment -> "использования"
                is CodeFragment -> "похожий код"
            }
        }.eachCount()
        val picked = listOf("правила", "документация", "использования", "похожий код").joinToString(", ") { "$it ${counts[it] ?: 0}" }
        val docs = retrieval.scopes.joinToString(", ").ifEmpty { "нет" }
        val code = retrieval.modules.joinToString(", ").ifEmpty { "нет" }
        return "$picked — из ${retrieval.docChunks} кусков документации ($docs) и ${retrieval.codeChunks} кусков кода ($code)"
    }

    private fun usage(spent: Spent) = LlmUsage(spent.model, spent.attempts, spent.promptTokens, spent.completionTokens,
        llm.cost(spent.promptTokens, spent.completionTokens))

    private companion object {
        const val RETRY_PAUSE_MS = 2000L
    }
}

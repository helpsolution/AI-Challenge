package advent.rag.eval

import advent.rag.agent.Agent
import advent.rag.agent.PlainAnswer
import advent.rag.agent.RagAnswer
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** Настройки из блока eval в application.yml. */
@ConfigurationProperties("eval")
data class EvalProperties(val questionsFile: Path, val reportFile: Path)

/** Контрольный вопрос: что должно быть в ответе и из каких документов корпуса это берётся. */
data class ControlQuestion(
    val id: Int,
    val question: String,
    val expectation: String,
    /**
     * Пути документов, как в поле source у чанков: data/corpus/intro.md.
     * Пусто — вопрос вне базы: с RAG агент должен честно сказать, что ответа нет.
     */
    val sources: List<String>,
)

/** Вопрос, ответы обоих режимов и то, насколько поиск попал в ожидаемые источники. */
data class EvalResult(val question: ControlQuestion, val plain: PlainAnswer, val rag: RagAnswer) {
    /** Место первого фрагмента из ожидаемого источника среди найденных, с 1; null — поиск его не нашёл. */
    val sourceRank: Int? = rag.sources.firstOrNull { it.source in question.sources }?.number

    /** Сколько найденных фрагментов взято из ожидаемых источников — context relevance из лекции. */
    val relevantChunks: Int = rag.sources.count { it.source in question.sources }
}

data class EvalReport(
    val questions: Int,
    /** Вопросы, ответ на которые есть в базе: по ним и считается попадание поиска. */
    val questionsWithSources: Int,
    /** В скольких из них поиск нашёл хотя бы один фрагмент из ожидаемого источника. */
    val sourceHits: Int,
    val reportFile: String,
    val results: List<EvalResult>,
)

/**
 * Прогон контрольных вопросов: каждый задаётся агенту в обоих режимах. Попадание поиска в источники
 * считается автоматически; верен ли ответ, человек решает по отчёту, сверяя его с ожиданием.
 */
@Component
class Evaluation(private val agent: Agent, private val json: JsonMapper, private val properties: EvalProperties) {

    fun run(): EvalReport {
        val file = properties.questionsFile
        require(Files.exists(file)) { "Нет файла контрольных вопросов $file" }
        val questions: List<ControlQuestion> = json.readValue(Files.readString(file))
        val results = questions.map { question ->
            log.info("Контрольный вопрос {}/{}: {}", question.id, questions.size, question.question)
            EvalResult(question, agent.answer(question.question), agent.answerWithRag(question.question))
        }
        Files.createDirectories(properties.reportFile.toAbsolutePath().parent)
        Files.writeString(properties.reportFile, markdown(results))
        val withSources = results.filter { it.question.sources.isNotEmpty() }
        return EvalReport(
            questions = results.size,
            questionsWithSources = withSources.size,
            sourceHits = withSources.count { it.sourceRank != null },
            reportFile = properties.reportFile.toString(),
            results = results,
        )
    }

    private fun markdown(results: List<EvalResult>): String = buildString {
        val topK = results.firstOrNull()?.rag?.sources?.size ?: 0
        val withSources = results.filter { it.question.sources.isNotEmpty() }
        appendLine("# Контрольные вопросы: без RAG и с RAG")
        appendLine()
        appendLine("Прогон ${Instant.now()} · top-K $topK")
        appendLine()
        appendLine("| # | Вопрос | Источник среди найденных | Фрагментов из источника |")
        appendLine("|---|---|---|---|")
        for (r in results) {
            val rank = when {
                r.question.sources.isEmpty() -> "— вне базы"
                r.sourceRank != null -> "✓ [${r.sourceRank}]"
                else -> "✗"
            }
            appendLine("| ${r.question.id} | ${r.question.question} | $rank | ${r.relevantChunks}/$topK |")
        }
        appendLine()
        appendLine(
            "Источник найден в ${withSources.count { it.sourceRank != null }} из ${withSources.size} вопросов, " +
                "ответ на которые есть в базе.",
        )
        for (r in results) {
            appendLine()
            appendLine("## ${r.question.id}. ${r.question.question}")
            appendLine()
            appendLine("**Ожидание:** ${r.question.expectation}")
            appendLine()
            val expected = r.question.sources.joinToString { "`$it`" }.ifEmpty { "нет — вопрос вне базы" }
            appendLine("**Ожидаемые источники:** $expected")
            appendLine()
            appendLine("**Найдено:**")
            for (s in r.rag.sources) {
                val mark = if (s.source in r.question.sources) " ✓" else ""
                appendLine("- [${s.number}] ${s.title} · ${"%.3f".format(s.score)} · `${s.chunkId}`$mark")
            }
            appendLine()
            appendLine("### Без RAG")
            appendLine()
            appendLine(r.plain.answer)
            appendLine()
            appendLine("### С RAG")
            appendLine()
            appendLine(r.rag.answer)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(Evaluation::class.java)
    }
}

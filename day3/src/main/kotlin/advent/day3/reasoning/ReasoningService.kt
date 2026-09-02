package advent.day3.reasoning

import advent.day3.llm.LlmException
import advent.day3.llm.LlmRunner
import advent.day3.llm.RunResult
import advent.day3.llm.RunSpec
import advent.day3.web.AnswerGroup
import advent.day3.web.ComparisonView
import advent.day3.web.SectionView
import advent.day3.web.SolveRequest
import advent.day3.web.SolveResponse
import advent.day3.web.StepView
import advent.day3.web.TechniqueRunView
import advent.day3.web.toView
import org.springframework.stereotype.Service
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Прогоняет одну задачу выбранными способами рассуждения и сводит результаты.
 *
 * Способы идут параллельно на виртуальных потоках: они друг от друга не зависят,
 * а ждать четыре ответа подряд — это четыре ожидания вместо одного.
 * Внутри способа вызовы последовательны там, где второй нуждается в первом.
 */
@Service
class ReasoningService(
    private val runner: LlmRunner,
    private val prompts: TechniquePrompts,
    private val extractor: AnswerExtractor,
) {

    fun solve(request: SolveRequest): SolveResponse {
        val marker = request.answerMarker.takeIf { request.requireAnswerLine && it.isNotBlank() }
        val techniques = request.techniques.distinct()

        val startedAt = System.nanoTime()
        val outcomes = parallel(techniques.map { technique -> { execute(technique, request, marker) } })
        val wallClockMs = (System.nanoTime() - startedAt) / 1_000_000

        // Упал ровно один способ — показываем остальные и красную карточку вместо него.
        // Упали все — причина общая (ключ, баланс, сеть), и честнее отдать её одной ошибкой.
        outcomes.firstOrNull()?.error?.takeIf { outcomes.all { o -> o.error != null } }?.let { throw it }

        val runs = outcomes.map { it.view }
        return SolveResponse(
            task = request.task,
            answerMarker = marker,
            runs = runs,
            comparison = compare(runs),
            wallClockMs = wallClockMs,
        )
    }

    private fun execute(technique: Technique, request: SolveRequest, marker: String?): Outcome = try {
        val steps = when (technique) {
            Technique.META_PROMPT -> metaPromptSteps(request, marker)
            else -> singleStep(technique, request, marker)
        }
        Outcome(view = summarize(technique, steps, marker))
    } catch (e: LlmException) {
        Outcome(
            view = TechniqueRunView(
                technique = technique,
                title = technique.title,
                summary = technique.summary,
                failure = e.message ?: "Способ не отработал",
            ),
            error = e,
        )
    }

    /** Прямой ответ, пошаговое решение и группа экспертов отличаются только системной инструкцией. */
    private fun singleStep(technique: Technique, request: SolveRequest, marker: String?): List<Step> {
        val systemPrompt = prompts.systemPrompt(technique, marker)
        return listOf(step("Запрос", systemPrompt, request.task, run(request, request.task, systemPrompt)))
    }

    /**
     * Мета-промпт: первый вызов пишет инструкцию, второй по ней решает.
     * Второму системная инструкция способа уже не нужна — её место занял промпт от модели.
     */
    private fun metaPromptSteps(request: SolveRequest, marker: String?): List<Step> {
        val authorPrompt = prompts.metaAuthorPrompt()
        val authored = run(request, request.task, authorPrompt)
        val generated = unwrapCodeFence(authored.answer)

        val answerLine = prompts.answerLine(marker)
        val solved = run(request, generated, answerLine)

        return listOf(
            step("Шаг 1 — модель пишет промпт", authorPrompt, request.task, authored),
            step("Шаг 2 — решение по этому промпту", answerLine, generated, solved),
        )
    }

    private fun run(request: SolveRequest, prompt: String, systemPrompt: String?): RunResult =
        runner.run(
            RunSpec(
                prompt = prompt,
                systemPrompt = systemPrompt,
                model = request.model,
                temperature = request.params?.temperature,
                maxTokens = request.params?.maxTokens,
            ),
        )

    private fun summarize(technique: Technique, steps: List<Step>, marker: String?): TechniqueRunView {
        val last = steps.last()
        val answer = last.result.answer
        return TechniqueRunView(
            technique = technique,
            title = technique.title,
            summary = technique.summary,
            steps = steps.map { it.view },
            answer = answer,
            finalAnswer = extractor.extract(answer, marker),
            sections = Sections.split(answer).map { SectionView(it.title, it.body) },
            calls = steps.size,
            promptTokens = steps.sumOf { it.result.usage?.promptTokens ?: 0 },
            completionTokens = steps.sumOf { it.result.usage?.completionTokens ?: 0 },
            totalTokens = steps.sumOf { it.result.usage?.totalTokens ?: 0 },
            latencyMs = steps.sumOf { it.result.latencyMs },
        )
    }

    /**
     * Сводка сравнения. Способы группируются по нормализованному финальному ответу:
     * одна группа — все пришли к одному результату, несколько — разошлись.
     */
    private fun compare(runs: List<TechniqueRunView>): ComparisonView {
        val answered = runs.filter { it.failure == null && !it.finalAnswer.isNullOrBlank() }

        val groups = answered
            .groupBy { extractor.normalize(it.finalAnswer!!) }
            .filterKeys { it.isNotBlank() }
            .map { (_, group) ->
                AnswerGroup(
                    answer = group.first().finalAnswer!!,
                    techniques = group.map { it.technique },
                    count = group.size,
                )
            }
            .sortedByDescending { it.count }

        return ComparisonView(
            groups = groups,
            allAgree = if (answered.size >= 2) groups.size == 1 else null,
            fastest = answered.minByOrNull { it.latencyMs }?.technique,
            leanest = answered.minByOrNull { it.completionTokens }?.technique,
        )
    }

    /** Модель нередко оборачивает сочинённый промпт в ```-блок. В запрос он должен уйти без обёртки. */
    private fun unwrapCodeFence(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed
            .removePrefix("```").substringAfter('\n', "")
            .substringBeforeLast("```")
            .trim()
            .ifEmpty { trimmed }
    }

    private fun step(label: String, systemPrompt: String?, userPrompt: String, result: RunResult) = Step(
        result = result,
        view = StepView(
            label = label,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            answer = result.answer,
            model = result.model,
            finishReason = result.finishReason,
            usage = result.usage?.toView(),
            latencyMs = result.latencyMs,
            exchange = result.exchange.toView(),
        ),
    )

    private data class Step(val result: RunResult, val view: StepView)

    private data class Outcome(val view: TechniqueRunView, val error: LlmException? = null)

    private fun <T> parallel(tasks: List<() -> T>): List<T> =
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            executor.invokeAll(tasks.map { Callable(it) }).map { future ->
                try {
                    future.get()
                } catch (e: ExecutionException) {
                    // Иначе наружу уходит ExecutionException и пользователь видит «внутренняя ошибка»
                    // вместо настоящей причины отказа провайдера.
                    throw e.cause ?: e
                }
            }
        }
}

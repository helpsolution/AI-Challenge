package advent.day4.temperature

import advent.day4.llm.LlmException
import advent.day4.llm.LlmRunner
import advent.day4.llm.RunResult
import advent.day4.llm.RunSpec
import advent.day4.web.AnswerGroup
import advent.day4.web.CompareRequest
import advent.day4.web.CompareResponse
import advent.day4.web.ComparisonView
import advent.day4.web.LaneDistance
import advent.day4.web.LaneView
import advent.day4.web.MetricsView
import advent.day4.web.RunView
import advent.day4.web.toView
import org.springframework.stereotype.Service
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Прогоняет один и тот же запрос на нескольких температурах и сводит результаты.
 *
 * Меняется ровно один параметр: задача, модель, системная инструкция и потолок длины
 * у всех прогонов общие. Иначе разницу в ответах нельзя было бы отнести к температуре.
 *
 * Все прогоны всех температур идут параллельно на виртуальных потоках: они друг от друга
 * не зависят, а ждать девять ответов подряд — это девять ожиданий вместо одного.
 */
@Service
class TemperatureService(
    private val runner: LlmRunner,
    private val extractor: AnswerExtractor,
) {

    fun compare(request: CompareRequest): CompareResponse {
        val temperatures = request.temperatures.distinct().sorted()
        temperatures.forEach {
            require(it in MIN_TEMPERATURE..MAX_TEMPERATURE) {
                "temperature: значение $it вне диапазона $MIN_TEMPERATURE–$MAX_TEMPERATURE"
            }
        }

        val marker = request.answerMarker.trim().takeIf { request.requireAnswerLine && it.isNotEmpty() }
        val systemPrompt = answerLine(marker)

        val startedAt = System.nanoTime()
        val outcomes = parallel(
            temperatures.flatMap { temperature ->
                (1..request.runs).map { index -> { execute(request, systemPrompt, temperature, index, marker) } }
            },
        )
        val wallClockMs = (System.nanoTime() - startedAt) / 1_000_000

        // Упал один прогон — показываем остальные и красную плашку вместо него.
        // Упали все — причина общая (ключ, баланс, сеть), и честнее отдать её одной ошибкой.
        outcomes.firstOrNull()?.error?.takeIf { outcomes.all { o -> o.error != null } }?.let { throw it }

        val lanes = temperatures.map { temperature ->
            lane(temperature, outcomes.filter { it.temperature == temperature }.map { it.view })
        }

        return CompareResponse(
            prompt = request.prompt,
            model = request.model?.takeIf { it.isNotBlank() } ?: runner.defaultModel(),
            runs = request.runs,
            systemPrompt = systemPrompt,
            answerMarker = marker,
            lanes = lanes,
            comparison = compare(lanes, marker),
            wallClockMs = wallClockMs,
        )
    }

    private fun execute(
        request: CompareRequest,
        systemPrompt: String?,
        temperature: Double,
        index: Int,
        marker: String?,
    ): Outcome = try {
        val result = runner.run(
            RunSpec(
                prompt = request.prompt,
                systemPrompt = systemPrompt,
                model = request.model,
                temperature = temperature,
                maxTokens = request.maxTokens,
            ),
        )
        Outcome(temperature, view(index, result, marker))
    } catch (e: LlmException) {
        Outcome(
            temperature = temperature,
            view = RunView(index = index, failure = e.message ?: "Прогон не отработал"),
            error = e,
        )
    }

    private fun view(index: Int, result: RunResult, marker: String?) = RunView(
        index = index,
        answer = result.answer,
        // Без маркера последняя строка развёрнутого ответа — просто последняя строка,
        // выдавать её за «финальный ответ» было бы враньём.
        finalAnswer = marker?.let { extractor.extract(result.answer, it) },
        metrics = TextStats.of(result.answer).toView(),
        model = result.model,
        finishReason = result.finishReason,
        usage = result.usage?.toView(),
        latencyMs = result.latencyMs,
        exchange = result.exchange.toView(),
    )

    private fun lane(temperature: Double, runs: List<RunView>): LaneView {
        val ok = runs.filter { it.failure == null }
        val answers = ok.mapNotNull { it.answer }

        return LaneView(
            temperature = temperature,
            band = TemperatureBand.of(temperature),
            title = TemperatureBand.of(temperature).title,
            emoji = TemperatureBand.of(temperature).emoji,
            runs = runs,
            metrics = average(ok.mapNotNull { it.metrics }),
            selfSpread = Similarity.spread(answers),
            distinctAnswers = answers.distinct().size.takeIf { answers.size >= 2 },
            completionTokens = ok.sumOf { it.usage?.completionTokens ?: 0 },
            latencyMs = if (ok.isEmpty()) 0 else ok.sumOf { it.latencyMs } / ok.size,
            failures = runs.size - ok.size,
        )
    }

    /** Метрики полосы — среднее по её удавшимся прогонам. */
    private fun average(metrics: List<MetricsView>): MetricsView? {
        if (metrics.isEmpty()) return null
        return MetricsView(
            chars = metrics.map { it.chars }.average().toInt(),
            words = metrics.map { it.words }.average().toInt(),
            sentences = metrics.map { it.sentences }.average().toInt(),
            avgSentenceWords = round2(metrics.map { it.avgSentenceWords }.average()),
            lexicalVariety = round2(metrics.map { it.lexicalVariety }.average()),
        )
    }

    /**
     * Сводка. Главное здесь — расстояния между полосами: они отвечают на вопрос,
     * меняет ли температура ответ вообще, и работают даже при одном прогоне на полосу.
     */
    private fun compare(lanes: List<LaneView>, marker: String?): ComparisonView {
        val answered = lanes.filter { it.metrics != null }
        val answersOf = { lane: LaneView -> lane.runs.mapNotNull { it.answer } }

        val distances = answered.indices.flatMap { i ->
            (i + 1 until answered.size).mapNotNull { j ->
                Similarity.spreadBetween(answersOf(answered[i]), answersOf(answered[j]))
                    ?.let { LaneDistance(answered[i].temperature, answered[j].temperature, it) }
            }
        }

        return ComparisonView(
            distances = distances,
            richest = answered.maxByOrNull { it.metrics!!.lexicalVariety }?.temperature,
            mostVaried = answered.filter { it.selfSpread != null }.maxByOrNull { it.selfSpread!! }?.temperature,
            longest = answered.maxByOrNull { it.metrics!!.words }?.temperature,
            shortest = answered.minByOrNull { it.metrics!!.words }?.temperature,
            fastest = answered.filter { it.latencyMs > 0 }.minByOrNull { it.latencyMs }?.temperature,
            answerGroups = if (marker == null) emptyList() else answerGroups(lanes),
        )
    }

    /**
     * Финальные строки всех прогонов, сгруппированные по нормализованному виду.
     * Одна группа — на всех температурах ответ один; несколько — где-то поплыло.
     * Работает только с маркером: без него сравнивать нечего.
     */
    private fun answerGroups(lanes: List<LaneView>): List<AnswerGroup> = lanes
        .flatMap { lane -> lane.runs.mapNotNull { it.finalAnswer?.let { answer -> lane.temperature to answer } } }
        .groupBy { extractor.normalize(it.second) }
        .filterKeys { it.isNotBlank() }
        .map { (_, group) ->
            AnswerGroup(
                answer = group.first().second,
                count = group.size,
                temperatures = group.map { it.first }.distinct().sorted(),
            )
        }
        .sortedByDescending { it.count }

    /** Единственная инструкция, которую приложение может добавить к запросу. */
    private fun answerLine(marker: String?): String? = marker?.let {
        "Последней строкой ответа напиши ровно: «$it <ответ>», где <ответ> — только сам ответ " +
            "(число, слово или короткая фраза), без пояснений и без повторения условия."
    }

    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0

    private data class Outcome(val temperature: Double, val view: RunView, val error: LlmException? = null)

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

    private companion object {
        const val MIN_TEMPERATURE = 0.0
        const val MAX_TEMPERATURE = 2.0
    }
}

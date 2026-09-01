package advent.day2.compare

import advent.day2.chat.ChatService
import advent.day2.chat.RunSpec
import advent.day2.format.ComplianceChecker
import advent.day2.format.Constraints
import advent.day2.format.PromptBuilder
import advent.day2.format.ResponseFormat
import advent.day2.web.ChatResponse
import advent.day2.web.CompareRequest
import advent.day2.web.CompareResponse
import advent.day2.web.DeterminismRequest
import advent.day2.web.DeterminismResponse
import advent.day2.web.LlmParams
import advent.day2.web.RunView
import advent.day2.web.SignatureGroup
import org.springframework.stereotype.Service
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Сравнение одного запроса при разном уровне контроля и проверка того,
 * что формат воспроизводится от прогона к прогону.
 *
 * Прогоны идут параллельно на виртуальных потоках: ждать пять последовательных
 * ответов модели нет смысла, они друг от друга не зависят.
 */
@Service
class CompareService(
    private val chatService: ChatService,
    private val promptBuilder: PromptBuilder,
    private val checker: ComplianceChecker,
) {

    fun compare(request: CompareRequest): CompareResponse {
        val systemPrompt = promptBuilder.build(request.constraints)

        val (baseline, constrained) = parallel(
            { chatService.run(baselineSpec(request)) },
            { chatService.run(constrainedSpec(request, systemPrompt)) },
        )

        return CompareResponse(
            prompt = request.prompt,
            systemPrompt = systemPrompt,
            // Свободный ответ проверяем теми же мерками: видно, что без инструкций формат не держится.
            baseline = view("Без ограничений", baseline, request.constraints, null),
            constrained = view("С ограничениями", constrained, request.constraints, systemPrompt),
        )
    }

    fun determinism(request: DeterminismRequest): DeterminismResponse {
        val systemPrompt = promptBuilder.build(request.constraints)
        val spec = constrainedSpec(
            CompareRequest(request.prompt, request.model, request.params, request.constraints),
            systemPrompt,
        )

        val responses = parallel((1..request.runs).map { { chatService.run(spec) } })
        val views = responses.mapIndexed { index, response ->
            view("Прогон ${index + 1}", response, request.constraints, systemPrompt)
        }

        val signatures = views.mapNotNull { it.compliance.structureSignature }
        val groups = signatures.groupingBy { it }.eachCount()
            .map { (signature, count) -> SignatureGroup(signature, count) }
            .sortedByDescending { it.count }

        val comparable = request.constraints.format == ResponseFormat.JSON
        val passed = views.count { it.compliance.passed }

        return DeterminismResponse(
            runs = request.runs,
            systemPrompt = systemPrompt,
            structureGroups = groups,
            structureIdentical = if (comparable) groups.size == 1 && signatures.size == request.runs else null,
            passedCount = passed,
            allPassed = passed == views.size,
            results = views,
        )
    }

    /** Эталон: ни системной инструкции, ни потолка длины, ни стоп-последовательности. */
    private fun baselineSpec(request: CompareRequest) = RunSpec(
        prompt = request.prompt,
        systemPrompt = null,
        model = request.model,
        params = request.params?.copy(maxTokens = null, stop = null),
        jsonMode = false,
    )

    private fun constrainedSpec(request: CompareRequest, systemPrompt: String?): RunSpec {
        val c = request.constraints
        val stop = c.stopSequence?.takeIf { it.isNotBlank() }?.let { listOf(it) }
        val params = (request.params ?: LlmParams()).copy(
            maxTokens = c.maxTokens ?: request.params?.maxTokens,
            stop = stop ?: request.params?.stop,
        )
        return RunSpec(
            prompt = request.prompt,
            systemPrompt = systemPrompt,
            model = request.model,
            params = params,
            jsonMode = c.jsonMode && c.format == ResponseFormat.JSON,
        )
    }

    private fun view(
        label: String,
        response: ChatResponse,
        constraints: Constraints,
        systemPrompt: String?,
    ) = RunView(
        label = label,
        answer = response.answer,
        model = response.model,
        latencyMs = response.latencyMs,
        usage = response.usage,
        finishReason = response.finishReason,
        systemPrompt = systemPrompt,
        appliedParams = response.appliedParams,
        compliance = checker.check(response.answer, constraints, response.finishReason),
        exchange = response.exchange,
    )

    private fun <T> parallel(vararg tasks: () -> T): List<T> = parallel(tasks.toList())

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

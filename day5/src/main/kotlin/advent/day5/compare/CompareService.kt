package advent.day5.compare

import advent.day5.catalog.ModelCard
import advent.day5.catalog.ModelCatalog
import advent.day5.catalog.ModelTier
import advent.day5.llm.LlmException
import advent.day5.llm.LlmRunner
import advent.day5.llm.RunResult
import advent.day5.llm.RunSpec
import advent.day5.web.CompareRequest
import advent.day5.web.CompareResponse
import advent.day5.web.ContenderView
import advent.day5.web.CostView
import advent.day5.web.toView
import org.springframework.stereotype.Service
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Прогоняет один и тот же запрос через модели трёх уровней и сводит замеры.
 *
 * Меняется ровно один параметр — сама модель. Текст задачи, температура и потолок длины
 * у всех участников общие: иначе разницу в ответах нельзя было бы отнести к классу модели.
 * Настроек генерации в интерфейсе нет намеренно — они были заданием дня 4, а здесь только
 * помешали бы: подкрутив температуру одному участнику, сравнение уровней уже не получишь.
 *
 * Все три запроса уходят параллельно на виртуальных потоках. Это не только экономит
 * время: последовательный опрос мерил бы ещё и очередь — слабая модель, стоящая
 * третьей, показала бы задержку сильной, отработавшей до неё.
 */
@Service
class CompareService(
    private val runner: LlmRunner,
    private val catalog: ModelCatalog,
) {

    fun compare(request: CompareRequest): CompareResponse {
        val chosen = resolve(request.models)
        require(chosen.isNotEmpty()) { "Каталог моделей пуст: проверьте llm.openrouter.catalog" }

        val startedAt = System.nanoTime()
        val outcomes = parallel(chosen.map { card -> { execute(request, card) } })
        val wallClockMs = (System.nanoTime() - startedAt) / 1_000_000

        // Упал один участник — показываем остальных и красную плашку вместо него.
        // Упали все — причина общая (ключ, баланс, сеть), и честнее отдать её одной ошибкой.
        outcomes.firstOrNull()?.error?.takeIf { outcomes.all { o -> o.error != null } }?.let { throw it }

        val contenders = rank(outcomes.map { it.view })

        return CompareResponse(
            prompt = request.prompt,
            temperature = TEMPERATURE,
            maxTokens = MAX_TOKENS,
            contenders = contenders,
            wallClockMs = wallClockMs,
            totalCost = contenders.sumOf { it.cost?.amount ?: 0.0 },
            costEstimated = contenders.any { it.cost?.estimated == true },
        )
    }

    /** Уровни идут в фиксированном порядке слабая → сильная: так разница читается как шкала. */
    private fun resolve(requested: Map<ModelTier, String>): List<ModelCard> =
        ModelTier.entries.mapNotNull { tier ->
            when (val id = requested[tier]) {
                null -> catalog.defaultOf(tier)
                else -> {
                    val card = catalog.card(id)
                    requireNotNull(card) { "Модель '$id' не входит в каталог" }
                    require(card.tier == tier) {
                        "Модель '$id' относится к уровню «${card.tier.title}», а не «${tier.title}»"
                    }
                    card
                }
            }
        }

    private fun execute(request: CompareRequest, card: ModelCard): Outcome = try {
        val result = runner.run(
            RunSpec(
                prompt = request.prompt,
                model = card.id,
                temperature = TEMPERATURE,
                maxTokens = MAX_TOKENS,
            ),
        )
        Outcome(card, view(card, result))
    } catch (e: LlmException) {
        Outcome(
            card = card,
            view = base(card).copy(failure = e.message ?: "Модель не отработала"),
            error = e,
        )
    }

    private fun base(card: ModelCard) = ContenderView(
        tier = card.tier,
        tierTitle = card.tier.title,
        tierEmoji = card.tier.emoji,
        model = card.toView(),
    )

    private fun view(card: ModelCard, result: RunResult): ContenderView {
        val usage = result.usage
        val completionTokens = usage?.completionTokens ?: 0

        return base(card).copy(
            answer = result.answer,
            reasoning = result.reasoning?.takeIf { it.isNotBlank() },
            metrics = TextStats.of(result.answer).toView(),
            latencyMs = result.latencyMs,
            usage = usage?.toView(),
            cost = cost(card, result),
            // Токенов в секунду, а не «время ответа»: скорость не должна зависеть от того,
            // насколько длинный ответ модель решила написать.
            tokensPerSecond = if (result.latencyMs > 0 && completionTokens > 0) {
                Formats.round(completionTokens * 1000.0 / result.latencyMs)
            } else {
                null
            },
            provider = result.provider,
            finishReason = result.finishReason,
            exchange = result.exchange.toView(),
        )
    }

    /**
     * Стоимость запроса. Первым делом берём сумму, которую провайдер реально списал:
     * это факт, а не оценка. Если её нет — считаем сами по прайсу и помечаем цифру
     * приблизительной, чтобы интерфейс не выдавал прикидку за факт.
     */
    private fun cost(card: ModelCard, result: RunResult): CostView? {
        val usage = result.usage ?: return null

        usage.cost?.let { return CostView(amount = it, estimated = false, per1000Requests = it * 1_000) }

        val promptPrice = card.promptPricePerMillion ?: return null
        val completionPrice = card.completionPricePerMillion ?: return null
        val amount = (usage.promptTokens * promptPrice + usage.completionTokens * completionPrice) / 1_000_000
        return CostView(amount = amount, estimated = true, per1000Requests = amount * 1_000)
    }

    /** Достраивает участникам кратности — их видно только на фоне остальных. */
    private fun rank(contenders: List<ContenderView>): List<ContenderView> {
        val ok = contenders.filter { it.failure == null }
        val fastestMs = ok.filter { it.latencyMs > 0 }.minOfOrNull { it.latencyMs }
        val cheapest = ok.mapNotNull { it.cost?.amount }.filter { it > 0 }.minOrNull()

        return contenders.map { contender ->
            if (contender.failure != null) return@map contender
            val amount = contender.cost?.amount

            contender.copy(
                latencyRatio = fastestMs
                    ?.takeIf { it > 0 && contender.latencyMs > 0 }
                    ?.let { Formats.round(contender.latencyMs.toDouble() / it) },
                costRatio = cheapest
                    ?.takeIf { amount != null && amount > 0 }
                    ?.let { Formats.round(amount!! / it) },
            )
        }
    }

    private data class Outcome(val card: ModelCard, val view: ContenderView, val error: LlmException? = null)

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
        /**
         * Параметры генерации у всех участников общие и не настраиваются.
         *
         * Температура низкая: сравниваются уровни моделей, а разброс внутри одной модели
         * был заданием дня 4. Потолок взят с запасом — reasoning-модели тратят на размышление
         * сотни токенов из этого же лимита, и на тысяче с небольшим флагман обрывается,
         * не дописав ответ.
         */
        const val TEMPERATURE = 0.2
        const val MAX_TOKENS = 2_000
    }
}

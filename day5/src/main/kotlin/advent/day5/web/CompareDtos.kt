package advent.day5.web

import advent.day5.catalog.ModelTier
import advent.day5.compare.TextMetrics
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Один запрос, три модели разного класса. Всё остальное — текст задачи, температура,
 * потолок длины — у прогонов общее: меняется ровно одно, сама модель. Иначе разницу
 * в ответах нельзя было бы отнести к её уровню.
 */
data class CompareRequest(
    @field:NotBlank(message = "Введите запрос")
    @field:Size(max = 8_000, message = "Запрос длиннее 8000 символов")
    val prompt: String,

    /**
     * Какую модель взять на каждый уровень. Пропущенный уровень берёт модель
     * по умолчанию: сравнение всегда идёт по всем трём — в этом смысл задания.
     */
    val models: Map<ModelTier, String> = emptyMap(),
)

data class MetricsView(val chars: Int, val words: Int, val sentences: Int)

fun TextMetrics.toView() = MetricsView(chars, words, sentences)

/**
 * Деньги за один запрос. `estimated = true` означает, что провайдер не прислал
 * списанную сумму и она посчитана нами по прайсу — цифра приблизительная,
 * и интерфейс обязан это показать.
 */
data class CostView(
    val amount: Double,
    val estimated: Boolean,
    /** Столько же запросов, но тысяча: 0.0000041 доллара человеку ни о чём не говорит. */
    val per1000Requests: Double,
)

/** Всё, что получилось у одной модели. */
data class ContenderView(
    val tier: ModelTier,
    val tierTitle: String,
    val tierEmoji: String,
    val model: ModelCardView,
    val answer: String? = null,
    /** Цепочка рассуждения, если модель её отдала отдельно от ответа. */
    val reasoning: String? = null,
    val metrics: MetricsView? = null,
    val latencyMs: Long = 0,
    val usage: UsageView? = null,
    val cost: CostView? = null,
    /** Выходных токенов в секунду. Это и есть скорость — в отличие от времени ответа,
     *  она не зависит от того, насколько длинный ответ модель решила написать. */
    val tokensPerSecond: Double? = null,
    /** Во сколько раз медленнее самого быстрого участника. У самого быстрого — 1.0. */
    val latencyRatio: Double? = null,
    /** Во сколько раз дороже самого дешёвого участника. */
    val costRatio: Double? = null,
    /** Кто на самом деле обслужил запрос: OpenRouter маршрутизирует на разные площадки. */
    val provider: String? = null,
    val finishReason: String? = null,
    val exchange: ExchangeView? = null,
    /** Заполняется, если этот участник упал: остальные всё равно показываются. */
    val failure: String? = null,
)

data class CompareResponse(
    val prompt: String,
    /** Параметры генерации, одинаковые у всех участников. Не настраиваются — см. CompareService. */
    val temperature: Double,
    val maxTokens: Int,
    val contenders: List<ContenderView>,
    /** Реальное время прогона: модели опрашиваются параллельно, поэтому меньше суммы. */
    val wallClockMs: Long,
    /** Сколько стоило сравнение целиком. */
    val totalCost: Double,
    val costEstimated: Boolean,
)

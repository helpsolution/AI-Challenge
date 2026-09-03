package advent.day4.web

import advent.day4.temperature.TemperatureBand
import advent.day4.temperature.TextMetrics
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size

/**
 * Один запрос, несколько температур. Всё остальное — задача, модель, потолок длины,
 * системная инструкция — у прогонов общее: меняется ровно один параметр.
 */
data class CompareRequest(
    @field:NotBlank(message = "Введите запрос")
    @field:Size(max = 8_000, message = "Запрос длиннее 8000 символов")
    val prompt: String,

    val model: String? = null,

    /** Значения температуры, каждое от 0.0 до 2.0. Диапазон проверяется в сервисе. */
    @field:NotEmpty(message = "Выберите хотя бы одну температуру")
    @field:Size(max = 4, message = "Больше четырёх температур за прогон не сравниваем")
    val temperatures: List<Double> = listOf(0.0, 0.7, 1.2),

    /**
     * Сколько раз повторить запрос на каждой температуре. Один прогон показывает,
     * какой ответ получился; разброс внутри температуры виден только от двух и выше.
     */
    @field:Min(value = 1, message = "минимум 1 прогон")
    @field:Max(value = 5, message = "максимум 5 прогонов")
    val runs: Int = 1,

    @field:Min(value = 1, message = "минимум 1")
    @field:Max(value = 8_192, message = "максимум 8192")
    val maxTokens: Int? = null,

    /**
     * Требовать финальную строку с маркером. Нужна на задачах с одним правильным
     * ответом: три такие строки рядом показывают, где точность поплыла.
     * По умолчанию выключено — иначе запрос перестаёт быть голым.
     */
    val requireAnswerLine: Boolean = false,

    @field:Size(max = 32, message = "Маркер длиннее 32 символов")
    val answerMarker: String = "ОТВЕТ:",
)

data class MetricsView(
    val chars: Int,
    val words: Int,
    val sentences: Int,
    val avgSentenceWords: Double,
    val lexicalVariety: Double,
)

fun TextMetrics.toView() = MetricsView(chars, words, sentences, avgSentenceWords, lexicalVariety)

/** Один прогон: что вернулось на этой температуре в этот раз. */
data class RunView(
    val index: Int,
    val answer: String? = null,
    /** Строка после маркера. Заполняется, только если маркер запрошен. */
    val finalAnswer: String? = null,
    val metrics: MetricsView? = null,
    val model: String? = null,
    val finishReason: String? = null,
    val usage: UsageView? = null,
    val latencyMs: Long = 0,
    val exchange: ExchangeView? = null,
    /** Заполняется, если этот прогон упал: остальные всё равно показываются. */
    val failure: String? = null,
)

/** Всё, что получилось на одной температуре. */
data class LaneView(
    val temperature: Double,
    val band: TemperatureBand,
    val title: String,
    val emoji: String,
    val runs: List<RunView>,
    /** Метрики, усреднённые по удавшимся прогонам. null — не удался ни один. */
    val metrics: MetricsView? = null,
    /** Средний разброс между прогонами этой температуры. null при одном прогоне. */
    val selfSpread: Double? = null,
    /** Сколько среди прогонов различных ответов (дословно). null при одном прогоне. */
    val distinctAnswers: Int? = null,
    val completionTokens: Int = 0,
    /** Средняя латентность прогона. Прогоны идут параллельно, поэтому это не время полосы. */
    val latencyMs: Long = 0,
    val failures: Int = 0,
)

/** Расстояние между ответами двух температур. */
data class LaneDistance(
    val from: Double,
    val to: Double,
    val distance: Double,
)

/** Финальные строки, совпавшие между собой. Заполняется только при включённом маркере. */
data class AnswerGroup(
    val answer: String,
    val count: Int,
    /** На каких температурах встретился этот ответ. */
    val temperatures: List<Double>,
)

data class ComparisonView(
    /** Все пары температур: насколько ответ меняется при переходе от одной к другой. */
    val distances: List<LaneDistance> = emptyList(),
    /** Температура с самым богатым словарём и с самым большим разбросом внутри. */
    val richest: Double? = null,
    val mostVaried: Double? = null,
    val longest: Double? = null,
    val shortest: Double? = null,
    val fastest: Double? = null,
    /** Финальные ответы, сгруппированные по совпадению. Пусто, если маркер не запрашивался. */
    val answerGroups: List<AnswerGroup> = emptyList(),
)

data class CompareResponse(
    val prompt: String,
    val model: String,
    val runs: Int,
    /** Системная инструкция, общая для всех прогонов. null — не отправлялась. */
    val systemPrompt: String? = null,
    val answerMarker: String? = null,
    val lanes: List<LaneView>,
    val comparison: ComparisonView,
    /** Реальное время прогона: все запросы идут параллельно, поэтому меньше суммы латентностей. */
    val wallClockMs: Long,
)

/** Справочник диапазонов — интерфейс строит по нему таблицу «для каких задач какая температура». */
data class BandInfo(
    val id: TemperatureBand,
    val title: String,
    val emoji: String,
    val range: String,
    val summary: String,
    val bestFor: List<String>,
    val avoidFor: List<String>,
    /** Верхняя граница диапазона: фронт определяет по ней полосу, а не дублирует пороги у себя. */
    val upperBound: Double,
)

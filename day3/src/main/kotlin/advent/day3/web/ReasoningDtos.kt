package advent.day3.web

import advent.day3.reasoning.Technique
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size

/** Одна задача, несколько способов её решить. Модель и параметры общие для всех способов. */
data class SolveRequest(
    @field:NotBlank(message = "Введите задачу")
    @field:Size(max = 8_000, message = "Задача длиннее 8000 символов")
    val task: String,

    val model: String? = null,

    @field:Valid
    val params: LlmParams? = null,

    @field:NotEmpty(message = "Выберите хотя бы один способ")
    val techniques: List<Technique> = Technique.entries,

    /**
     * Требовать от каждого способа финальную строку с маркером. Единственная инструкция,
     * общая для всех четырёх способов — включая прямой ответ. Без неё финальные ответы
     * не с чем сопоставлять, поэтому по умолчанию включено, но выключается одним тумблером.
     */
    val requireAnswerLine: Boolean = true,

    @field:Size(max = 32, message = "Маркер длиннее 32 символов")
    val answerMarker: String = "ОТВЕТ:",
)

/** Один вызов модели внутри способа: что ушло, что вернулось. */
data class StepView(
    val label: String,
    val systemPrompt: String? = null,
    val userPrompt: String,
    val answer: String,
    val model: String,
    val finishReason: String? = null,
    val usage: UsageView? = null,
    val latencyMs: Long,
    val exchange: ExchangeView? = null,
)

data class SectionView(val title: String, val body: String)

/** Результат одного способа целиком. */
data class TechniqueRunView(
    val technique: Technique,
    val title: String,
    val summary: String,
    /** Все вызовы способа по порядку: у мета-промпта их два, у остальных — один. */
    val steps: List<StepView> = emptyList(),
    /** Полный текст последнего ответа — то, что способ выдал в качестве решения. */
    val answer: String? = null,
    /** Строка после маркера: только по ней способы и сравниваются. */
    val finalAnswer: String? = null,
    /** Разделы ответа, если он размечен заголовками. Заполняется у группы экспертов. */
    val sections: List<SectionView> = emptyList(),
    val calls: Int = 0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    /** Сумма по вызовам способа. Способы идут параллельно, поэтому это не общее время прогона. */
    val latencyMs: Long = 0,
    /** Заполняется, если способ упал: остальные способы всё равно показываются. */
    val failure: String? = null,
)

/** Группа способов, пришедших к одному и тому же финальному ответу. */
data class AnswerGroup(
    val answer: String,
    val techniques: List<Technique>,
    val count: Int,
)

data class ComparisonView(
    val groups: List<AnswerGroup> = emptyList(),
    /** null, если сравнивать не с чем: меньше двух способов дошли до финального ответа. */
    val allAgree: Boolean? = null,
    val fastest: Technique? = null,
    /** Способ, потративший меньше всего токенов на генерацию. */
    val leanest: Technique? = null,
)

data class SolveResponse(
    val task: String,
    /** Маркер, по которому вырезался финальный ответ. null — способы шли без общей добавки. */
    val answerMarker: String? = null,
    val runs: List<TechniqueRunView>,
    val comparison: ComparisonView,
    /** Реальное время прогона: способы идут параллельно, поэтому меньше суммы их латентностей. */
    val wallClockMs: Long,
)

/** Справочник способов — фронт берёт названия и описания отсюда, а не дублирует их у себя. */
data class TechniqueInfo(
    val id: Technique,
    val title: String,
    val summary: String,
    val calls: Int,
)

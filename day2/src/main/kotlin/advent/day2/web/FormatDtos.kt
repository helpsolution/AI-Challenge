package advent.day2.web

import advent.day2.format.ComplianceReport
import advent.day2.format.Constraints
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/** Один и тот же запрос, два прогона: свободный и зажатый ограничениями. */
data class CompareRequest(
    @field:NotBlank(message = "Введите запрос")
    @field:Size(max = 8_000, message = "Запрос длиннее 8000 символов")
    val prompt: String,

    val model: String? = null,

    @field:Valid
    val params: LlmParams? = null,

    @field:Valid
    val constraints: Constraints = Constraints.NONE,
)

/** Многократный прогон зажатого запроса — проверка, что формат держится от раза к разу. */
data class DeterminismRequest(
    @field:NotBlank(message = "Введите запрос")
    @field:Size(max = 8_000, message = "Запрос длиннее 8000 символов")
    val prompt: String,

    val model: String? = null,

    @field:Valid
    val params: LlmParams? = null,

    @field:Valid
    val constraints: Constraints = Constraints.NONE,

    @field:Min(value = 2, message = "Прогонов: минимум 2")
    @field:Max(value = 8, message = "Прогонов: максимум 8")
    val runs: Int = 3,
)

/** Результат одного прогона со всей диагностикой. */
data class RunView(
    val label: String,
    val answer: String,
    val model: String,
    val latencyMs: Long,
    val usage: UsageView? = null,
    val finishReason: String? = null,
    val systemPrompt: String? = null,
    val appliedParams: Map<String, Any> = emptyMap(),
    val compliance: ComplianceReport,
    val exchange: ExchangeView? = null,
)

data class CompareResponse(
    val prompt: String,
    val systemPrompt: String? = null,
    val baseline: RunView,
    val constrained: RunView,
)

data class SignatureGroup(val signature: String, val count: Int)

data class DeterminismResponse(
    val runs: Int,
    val systemPrompt: String? = null,
    /** Группы структур JSON: одна группа = формат держится, несколько = плывёт. */
    val structureGroups: List<SignatureGroup> = emptyList(),
    /** null, когда формат не JSON и структуру сравнивать не по чему. */
    val structureIdentical: Boolean? = null,
    val passedCount: Int,
    val allPassed: Boolean,
    val results: List<RunView>,
)

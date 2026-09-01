package advent.day2.format

import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/** Что удалось проверить в ответе и где модель нарушила заданный формат. */
data class ComplianceReport(
    val words: Int,
    val chars: Int,
    val lines: Int,
    val jsonValid: Boolean? = null,
    val schemaMissing: List<String> = emptyList(),
    val schemaExtra: List<String> = emptyList(),
    val itemCount: Int? = null,
    val sentences: Int? = null,
    val withinWordLimit: Boolean? = null,
    val withinItemLimit: Boolean? = null,
    val endMarkerFound: Boolean? = null,
    val finishReason: String? = null,
    val truncated: Boolean = false,
    /** Отпечаток структуры — заполняется, когда ответ разобрался как JSON. */
    val structureSignature: String? = null,
    val violations: List<String> = emptyList(),
) {
    val passed: Boolean get() = violations.isEmpty()
}

/**
 * Проверяет ответ на соответствие тем ограничениям, которые были заданы.
 * Ничего не «чинит» — только фиксирует факт: уложилась модель или нет.
 */
@Component
class ComplianceChecker(private val objectMapper: ObjectMapper) {

    fun check(answer: String, constraints: Constraints, finishReason: String?): ComplianceReport {
        val words = answer.split(WHITESPACE).count { it.isNotBlank() }
        val lines = answer.lines().count { it.isNotBlank() }
        val violations = mutableListOf<String>()
        val truncated = finishReason == "length"
        if (truncated) violations += "Ответ обрезан по max_tokens (finish_reason = length)"

        var jsonValid: Boolean? = null
        var signature: String? = null
        var missing: List<String> = emptyList()
        var extra: List<String> = emptyList()
        var itemCount: Int? = null
        var sentences: Int? = null

        when (constraints.format) {
            ResponseFormat.JSON -> {
                val node = JsonStructure.parse(objectMapper, answer)
                jsonValid = node != null
                if (node == null) {
                    violations += "Ответ не разбирается как JSON"
                } else {
                    signature = JsonStructure.signature(node)
                    val schema = constraints.jsonSchema?.takeIf { it.isNotBlank() }
                        ?.let { JsonStructure.parse(objectMapper, it) }
                    if (schema != null) {
                        val expected = JsonStructure.keyPaths(schema)
                        val actual = JsonStructure.keyPaths(node)
                        missing = (expected - actual).sorted()
                        extra = (actual - expected).sorted()
                        if (missing.isNotEmpty()) violations += "Нет полей схемы: ${missing.joinToString(", ")}"
                        if (extra.isNotEmpty()) violations += "Лишние поля: ${extra.joinToString(", ")}"
                    }
                    if (JsonStructure.extract(answer) != answer.trim()) {
                        violations += "JSON обёрнут посторонним текстом или markdown"
                    }
                }
            }

            ResponseFormat.BULLETS -> {
                itemCount = answer.lines().count { BULLET.containsMatchIn(it) }
                if (itemCount == 0) violations += "В ответе нет пунктов списка"
                val nonBullet = answer.lines().count { it.isNotBlank() && !BULLET.containsMatchIn(it) }
                if (nonBullet > 0) violations += "Есть $nonBullet строк вне списка"
            }

            ResponseFormat.TABLE -> {
                val tableLines = answer.lines().filter { it.trim().startsWith("|") }
                itemCount = (tableLines.size - 2).coerceAtLeast(0) // шапка и разделитель не в счёт
                if (tableLines.size < 3) violations += "Ответ не похож на markdown-таблицу"
            }

            ResponseFormat.SENTENCE -> {
                sentences = SENTENCE_END.split(answer.trim()).count { it.isNotBlank() }
                if (sentences > 1) violations += "Предложений больше одного: $sentences"
                if (answer.trim().contains('\n')) violations += "В ответе есть переносы строк"
            }

            ResponseFormat.FREE -> Unit
        }

        val withinWords = constraints.maxWords?.let { limit ->
            (words <= limit).also { if (!it) violations += "Слов $words при лимите $limit" }
        }

        val withinItems = constraints.maxItems?.let { limit ->
            val actual = itemCount ?: return@let null
            (actual <= limit).also { if (!it) violations += "Элементов $actual при лимите $limit" }
        }

        val marker = constraints.endMarker?.takeIf { it.isNotBlank() } ?: PromptBuilder.DEFAULT_MARKER
        val endMarkerFound = if (constraints.endMarkerInstruction) {
            // Именно в конце: «###» посреди markdown-заголовков маркером завершения не является.
            val found = answer.trimEnd().endsWith(marker)
            val cutByApi = !found && !constraints.stopSequence.isNullOrBlank() && finishReason == "stop"
            if (!found && !cutByApi) violations += "Маркер завершения $marker не найден"
            found
        } else {
            null
        }

        return ComplianceReport(
            words = words,
            chars = answer.length,
            lines = lines,
            jsonValid = jsonValid,
            schemaMissing = missing,
            schemaExtra = extra,
            itemCount = itemCount,
            sentences = sentences,
            withinWordLimit = withinWords,
            withinItemLimit = withinItems,
            endMarkerFound = endMarkerFound,
            finishReason = finishReason,
            truncated = truncated,
            structureSignature = signature,
            violations = violations,
        )
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val BULLET = Regex("^\\s*([-*•]|\\d+[.)])\\s+")
        val SENTENCE_END = Regex("(?<=[.!?…])\\s+")
    }
}

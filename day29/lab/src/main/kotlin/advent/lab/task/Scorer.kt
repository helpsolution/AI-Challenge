package advent.lab.task

import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import kotlin.math.abs

/** present = false: поля в ответе нет совсем. actual — значение как его написала модель. */
data class FieldCheck(val expected: Any?, val actual: JsonNode?, val present: Boolean, val ok: Boolean)

data class Verdict(val valid: Boolean, val error: String?, val fields: Map<String, FieldCheck>) {
    val correct: Int get() = fields.values.count { it.ok }
}

/**
 * Сверка ответа модели с разметкой. Структура строгая: нужен JSON-объект с ключами из задания, перечисления —
 * ровно значения из списка. Нестрогое только написание: «№ 4829157» и «4829157», «12 490» строкой и 12490 числом
 * считаются одним и тем же — проверяется понимание, а не то, как модель оформила число.
 */
@Component
class Scorer(private val json: JsonMapper) {

    fun score(text: String, expected: Fields): Verdict {
        val node = try {
            json.readTree(extractObject(text))
        } catch (e: JacksonException) {
            return invalid(expected, "JSON не разобран: ${e.originalMessage.take(160)}")
        }
        if (!node.isObject) return invalid(expected, "Ответ — не JSON-объект")
        return Verdict(valid = true, error = null, fields = linkedMapOf(
            "category" to enumField(node, "category", expected.category),
            "action" to enumField(node, "action", expected.action),
            "urgency" to enumField(node, "urgency", expected.urgency),
            "order_id" to field(node, "order_id", expected.orderId) { orderId(it) == expected.orderId?.let(::normalizeOrder) },
            "amount" to field(node, "amount", expected.amount) { actual ->
                val value = amount(actual)
                if (expected.amount == null) actual.isNull || isBlank(actual)
                else value != null && abs(value - expected.amount) < 0.5
            },
        ))
    }

    private fun enumField(node: JsonNode, name: String, expected: String?) =
        field(node, name, expected) { it.isString && it.stringValue().trim().lowercase() == expected }

    private fun field(node: JsonNode, name: String, expected: Any?, check: (JsonNode) -> Boolean): FieldCheck {
        val actual = node.get(name) ?: return FieldCheck(expected, null, present = false, ok = false)
        return FieldCheck(expected, actual, present = true, ok = check(actual))
    }

    private fun orderId(node: JsonNode): String? = when {
        node.isNull -> null
        node.isString -> normalizeOrder(node.stringValue()).ifEmpty { null }
        node.isNumber -> node.asString()
        else -> "?"
    }

    private fun amount(node: JsonNode): Double? = when {
        node.isNumber -> node.doubleValue()
        node.isString -> node.stringValue().replace(Regex("[\\s\\u00a0]"), "").replace(',', '.')
            .let { NUMBER.find(it)?.value?.toDoubleOrNull() }
        else -> null
    }

    private fun isBlank(node: JsonNode) = node.isString && node.stringValue().isBlank()

    private fun invalid(expected: Fields, error: String) = Verdict(valid = false, error = error,
        fields = FIELD_NAMES.associateWith { FieldCheck(expected[it], null, present = false, ok = false) })

    private companion object {
        val NUMBER = Regex("\\d+(\\.\\d+)?")

        // Сравниваются только буквы и цифры: «№ ZK-4471209», «zk4471209» и «ZK-4471209» — один номер.
        fun normalizeOrder(value: String) = value.uppercase().filter { it.isLetterOrDigit() }

        /**
         * Без JSON-схемы модель заворачивает ответ в ```json и пишет пояснения до и после. Берём первый объект
         * целиком: от первой «{» до парной ей «}», скобки внутри строк не считаются.
         */
        fun extractObject(text: String): String {
            val start = text.indexOf('{')
            if (start < 0) return text
            var depth = 0
            var inString = false
            var escaped = false
            for (i in start until text.length) {
                val c = text[i]
                when {
                    escaped -> escaped = false
                    inString && c == '\\' -> escaped = true
                    c == '"' -> inString = !inString
                    !inString && c == '{' -> depth++
                    !inString && c == '}' -> if (--depth == 0) return text.substring(start, i + 1)
                }
            }
            return text.substring(start)
        }
    }
}

package advent.day2.format

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Разбор ответа как JSON и «структурная подпись» — отпечаток формы данных без значений.
 * Именно по совпадению подписей проверяется, что модель отдаёт одинаковую структуру
 * от запроса к запросу.
 */
object JsonStructure {

    /**
     * Достаёт JSON из ответа: модель любит обернуть его в ```json … ``` или добавить
     * пояснение вокруг. Для проверки схемы это шум, а не нарушение структуры.
     */
    fun extract(text: String): String? {
        val cleaned = text.trim()
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```")
            .trim()

        if (cleaned.startsWith("{") || cleaned.startsWith("[")) return cleaned

        val start = cleaned.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        val open = cleaned[start]
        val close = if (open == '{') '}' else ']'
        val end = cleaned.lastIndexOf(close)
        return if (end > start) cleaned.substring(start, end + 1) else null
    }

    fun parse(mapper: ObjectMapper, text: String): JsonNode? = try {
        extract(text)?.let { mapper.readTree(it) }
    } catch (_: Exception) {
        null
    }

    /** Отпечаток формы: ключи и типы, значения отброшены, порядок ключей нормализован. */
    fun signature(node: JsonNode): String = when {
        node.isObject -> node.propertyNames().asSequence().sorted()
            .joinToString(",", "{", "}") { "$it:${signature(node.get(it))}" }

        node.isArray -> {
            val inner = node.values().asSequence().map { signature(it) }.distinct().sorted().toList()
            when (inner.size) {
                0 -> "[]"
                1 -> "[${inner.first()}]"
                else -> inner.joinToString("|", "[", "]")
            }
        }

        node.isTextual -> "string"
        node.isNumber -> "number"
        node.isBoolean -> "boolean"
        node.isNull -> "null"
        else -> "unknown"
    }

    /** Пути ключей вида `ingredients[].name` — по ним сверяем ответ со схемой. */
    fun keyPaths(node: JsonNode, prefix: String = ""): Set<String> = when {
        node.isObject -> node.propertyNames().asSequence().flatMap { name ->
            val path = if (prefix.isEmpty()) name else "$prefix.$name"
            sequenceOf(path) + keyPaths(node.get(name), path)
        }.toSet()

        node.isArray -> node.values().asSequence()
            .flatMap { keyPaths(it, "$prefix[]").asSequence() }
            .toSet()

        else -> emptySet()
    }
}

package advent.day2.format

import org.springframework.stereotype.Component

/**
 * Превращает набор переключателей в system-prompt. Текст показывается в интерфейсе
 * целиком: видно, что именно из настроек ушло модели.
 */
@Component
class PromptBuilder {

    fun build(constraints: Constraints): String? {
        if (constraints.isEmpty()) return null

        val blocks = buildList {
            formatBlock(constraints)?.let { add(it) }
            lengthBlock(constraints)?.let { add(it) }
            endingBlock(constraints)?.let { add(it) }
        }
        if (blocks.isEmpty()) return null

        return buildString {
            append("Ты отвечаешь строго по заданному формату. Нарушать формат нельзя.\n")
            blocks.forEach { append("\n").append(it).append("\n") }
        }.trim()
    }

    private fun formatBlock(c: Constraints): String? = when (c.format) {
        ResponseFormat.FREE -> null

        ResponseFormat.JSON -> buildString {
            append("ФОРМАТ: верни один валидный JSON-объект и ничего больше. ")
            append("Без markdown-обёртки, без ```json, без пояснений до или после.")
            if (!c.jsonSchema.isNullOrBlank()) {
                append("\nСтруктура должна в точности совпадать со схемой — те же ключи, ")
                append("та же вложенность, те же типы. Значения подставь по смыслу запроса:\n")
                append(c.jsonSchema.trim())
            }
        }

        ResponseFormat.BULLETS -> buildString {
            append("ФОРМАТ: маркированный список. Каждый пункт с новой строки и начинается с «- ». ")
            append("Никакого вступления и заключения — только пункты.")
            c.maxItems?.let { append("\nПунктов должно быть не больше $it.") }
        }

        ResponseFormat.TABLE -> buildString {
            append("ФОРМАТ: таблица в markdown с шапкой и строкой-разделителем. ")
            append("Только таблица, без текста вокруг.")
            c.maxItems?.let { append("\nСтрок данных должно быть не больше $it.") }
        }

        ResponseFormat.SENTENCE -> "ФОРМАТ: ровно одно предложение. Без списков, без переносов строк."
    }

    private fun lengthBlock(c: Constraints): String? {
        val limits = buildList {
            c.maxWords?.let { add("не более $it слов во всём ответе") }
            if (c.format !in setOf(ResponseFormat.BULLETS, ResponseFormat.TABLE)) {
                c.maxItems?.let { add("не более $it элементов в перечислениях") }
            }
        }
        if (limits.isEmpty()) return null
        return "ДЛИНА: " + limits.joinToString("; ") + ". Уложись в лимит, не обрывая мысль на полуслове."
    }

    private fun endingBlock(c: Constraints): String? {
        if (!c.endMarkerInstruction) return null
        val marker = c.endMarker?.takeIf { it.isNotBlank() } ?: DEFAULT_MARKER
        return "ЗАВЕРШЕНИЕ: закончив ответ, поставь на отдельной строке маркер $marker " +
            "и не пиши после него ничего."
    }

    companion object {
        const val DEFAULT_MARKER = "###"
    }
}

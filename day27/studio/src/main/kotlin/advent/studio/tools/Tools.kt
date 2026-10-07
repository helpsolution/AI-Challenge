package advent.studio.tools

import org.springframework.stereotype.Component

/** Подготовленный вызов модели: системный промпт, температура и что код сделает с ответом. */
class TextTask(
    val system: String,
    val temperature: Double,
    /** Пометка над результатом, например «кириллица → EN». */
    val note: String? = null,
    /** Код проверяет то, что вернула модель: например, что JSON действительно парсится. */
    val check: ((String) -> Check)? = null,
    val cleanup: (String) -> String = { it.trim() },
)

private class Language(val name: String, val code: String)

private val LANGUAGES = mapOf(
    "en" to Language("English", "EN"),
    "ru" to Language("Russian", "RU"),
    "de" to Language("German", "DE"),
    "es" to Language("Spanish", "ES"),
    "fr" to Language("French", "FR"),
)

private class Format(val name: String, val short: String, val check: (String) -> Check)

/**
 * На русский llama3.2:3b переводит хуже всего: оставляет английские слова («не Merge anything into main»).
 * Из проверенных вариантов лучший — короткий промпт с ролью «English-to-Russian» и требованием писать кириллицей.
 * Общая фраза про форматирование здесь мешает: с ней вторая фраза примера снова остаётся английской.
 */
private const val RUSSIAN_TRANSLATOR =
    "You are a professional English-to-Russian translator. Translate the text from the user into natural, fluent Russian. " +
        "Every word must be in Russian, written in Cyrillic: translate technical terms too (deploy — деплой, merge — вливать, staging — стейджинг). " +
        "Keep the meaning, tone and formatting. Reply with the Russian translation only."

private const val REPLY_ONLY = "Reply with the result only, without explanations, notes or quotes."

/** Инструменты мастерской. Сообщение коммита сюда не входит: его пишет CommitWriter из модуля local-llm. */
@Component
class Tools(private val checks: Checks) {
    private val formats = mapOf(
        "json" to Format("JSON", "JSON", checks::json),
        "yaml" to Format("YAML", "YAML", checks::yaml),
        "csv" to Format("CSV with a header row", "CSV", checks::csv),
        "markdown" to Format("a Markdown table", "Markdown table", checks::markdownTable),
    )

    // Инструкции по-английски: русские варианты (по 3 прогона на режим) чаще вставляли в русский текст
    // английские слова («visibility», «remotely», «ВTomorrow») и выдумывали факты (срок «до пятницы 13 декабря»).
    private val edits = mapOf(
        "fix" to "Fix spelling, grammar and punctuation in the text from the user. Do not change the meaning, the words or the style.",
        "shorten" to "Shorten the text from the user to about half of its length. Keep the key facts, drop the filler.",
        "formal" to "Rewrite the text from the user in a formal business style. Keep all the facts.",
        "simple" to "Rewrite the text from the user in simple, friendly words, as if writing to a colleague. Keep all the facts.",
    )

    fun prepare(tool: String, option: String, text: String): TextTask = when (tool) {
        "translate" -> translate(option, text)
        "convert" -> convert(option)
        "edit" -> edit(option)
        else -> throw IllegalArgumentException("Неизвестный инструмент: $tool")
    }

    private fun translate(option: String, text: String): TextTask {
        // «Авто» решает код, а не модель: кириллица → английский, остальное → русский.
        val auto = option == "auto"
        val cyrillic = text.count { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
        val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        val target = if (auto) (if (cyrillic > latin) "en" else "ru") else option
        val language = LANGUAGES[target] ?: throw IllegalArgumentException("Неизвестный язык перевода: $option")
        val note = if (auto) "${if (cyrillic > latin) "кириллица" else "латиница"} → ${language.code}" else "→ ${language.code}"
        if (target == "ru") return TextTask(RUSSIAN_TRANSLATOR, temperature = 0.0, note = note)
        return TextTask(
            system = "You are a professional translator. Translate the text from the user into natural, fluent ${language.name}. " +
                "Keep the meaning, tone and formatting: line breaks, lists, markdown, code and URLs stay as they are. " +
                "Reply with the ${language.name} translation only, without notes or quotes.",
            temperature = 0.0,
            note = note,
        )
    }

    private fun convert(option: String): TextTask {
        val format = formats[option] ?: throw IllegalArgumentException("Неизвестный формат: $option")
        return TextTask(
            system = "Convert the data from the user into ${format.name}. The input can be JSON, YAML, CSV, XML, a table or plain text: " +
                "extract the records and fields from it. Keep every record and every value exactly, do not invent or drop anything. " +
                "Reply with the ${format.short} only, without explanations and without code fences.",
            temperature = 0.1,
            check = format.check,
            cleanup = ::stripCodeFence,
        )
    }

    private fun edit(option: String): TextTask {
        val instruction = edits[option] ?: throw IllegalArgumentException("Неизвестный режим редактуры: $option")
        return TextTask(system = "$instruction Answer in the same language as the text. $REPLY_ONLY", temperature = 0.2)
    }
}

private val FENCED = Regex("```[\\w-]*\\n(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL)

/** Модель любит заворачивать ответ в ```json … ``` даже после прямого запрета — снимаем обёртку. */
private fun stripCodeFence(text: String): String = (FENCED.find(text)?.groupValues?.get(1) ?: text).trim()

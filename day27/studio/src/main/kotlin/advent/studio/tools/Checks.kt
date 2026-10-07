package advent.studio.tools

import org.springframework.stereotype.Component
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

class Check(val ok: Boolean, val label: String)

/** Модель не гарантирует формат — результат конвертера проверяет код тем же парсером, что и любая программа. */
@Component
class Checks {
    // Без FAIL_ON_TRAILING_TOKENS «{"a":1} и ещё текст» сошёл бы за валидный JSON.
    private val mapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()

    fun json(text: String): Check = try {
        val node = mapper.readTree(text)
        when {
            node.isArray -> Check(true, "JSON парсится · массив из ${count(node.size(), "элемента", "элементов", "элементов")}")
            node.isObject -> Check(true, "JSON парсится · объект, ${count(node.size(), "поле", "поля", "полей")}")
            else -> Check(false, "в ответе нет JSON-объекта или массива")
        }
    } catch (e: JacksonException) {
        Check(false, "не парсится как JSON: ${e.originalMessage}")
    }

    fun yaml(text: String): Check = try {
        when (val value = Yaml(SafeConstructor(LoaderOptions())).load<Any?>(text)) {
            is List<*> -> Check(true, "YAML парсится · список из ${count(value.size, "элемента", "элементов", "элементов")}")
            is Map<*, *> -> Check(true, "YAML парсится · ${count(value.size, "ключ", "ключа", "ключей")}")
            else -> Check(false, "YAML разобрался как одна строка — структуры нет")
        }
    } catch (e: YAMLException) {
        Check(false, "не парсится как YAML: ${e.message?.lineSequence()?.first()}")
    }

    fun csv(text: String): Check {
        val rows = text.lines().filter { it.isNotBlank() }.map(::csvFields)
        if (rows.size < 2) return Check(false, "в CSV нет строк под заголовком")
        val width = rows.first().size
        val bad = rows.indexOfFirst { it.size != width }
        if (bad >= 0) return Check(false, "в строке ${bad + 1} ${count(rows[bad].size, "колонка", "колонки", "колонок")} вместо $width")
        return Check(true, "CSV · ${count(rows.size - 1, "строка", "строки", "строк")} × ${count(width, "колонка", "колонки", "колонок")}")
    }

    fun markdownTable(text: String): Check {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 3 || lines.any { !it.startsWith("|") || !it.endsWith("|") }) {
            return Check(false, "не Markdown-таблица: каждая строка должна начинаться и заканчиваться на |")
        }
        if (!SEPARATOR.matches(lines[1])) return Check(false, "под заголовком нет разделителя |---|")
        val width = cells(lines[0])
        val bad = lines.indexOfFirst { cells(it) != width }
        if (bad >= 0) return Check(false, "в строке ${bad + 1} ${count(cells(lines[bad]), "ячейка", "ячейки", "ячеек")} вместо $width")
        return Check(true, "Markdown-таблица · ${count(lines.size - 2, "строка", "строки", "строк")} × ${count(width, "колонка", "колонки", "колонок")}")
    }

    private fun cells(line: String) = line.removePrefix("|").removeSuffix("|").split("|").size

    /** Поля одной строки CSV: запятые внутри кавычек не разделяют. */
    private fun csvFields(line: String): List<String> {
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        for (c in line) {
            when {
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> fields += field.toString().also { field.clear() }
                else -> field.append(c)
            }
        }
        return fields + field.toString()
    }

    private companion object {
        val SEPARATOR = Regex("""^\|(\s*:?-{3,}:?\s*\|)+$""")
    }
}

/** 1 строка, 3 строки, 5 строк. */
private fun count(n: Int, one: String, few: String, many: String): String {
    val word = when {
        n % 10 == 1 && n % 100 != 11 -> one
        n % 10 in 2..4 && n % 100 !in 12..14 -> few
        else -> many
    }
    return "$n $word"
}

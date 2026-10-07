package advent.localllm.commit

import advent.localllm.ChatResult
import advent.localllm.Message
import advent.localllm.OllamaClient
import advent.localllm.OllamaException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Столько символов диффа уходит в модель. Список файлов (`--stat`) уходит всегда целиком. */
const val DIFF_LIMIT = 12_000

private const val FIRST_TEMPERATURE = 0.2

/** «Ещё вариант» с той же температурой 3B-модель почти дословно повторяет — даём ей больше свободы. */
private const val RETRY_TEMPERATURE = 0.8

private val SYSTEM_PROMPT = """
    You write git commit messages in the Conventional Commits style.
    You get the list of changed files and the staged diff. Reply with JSON:
    - type: feat (new functionality), fix (bug fix), docs (documentation only), style, refactor, perf, test, build, ci or chore.
    - subject: one line in imperative mood ("add", "fix", "remove", never "added" or "updates"), starts lowercase, no trailing period, at most 60 characters.
    - body: 0-3 short lines on what changed and why. Empty for a small change.
    Describe only what the diff shows. Do not invent features. Always write in English.
""".trimIndent()

private val SCHEMA: JsonElement = Json.parseToJsonElement(
    """
    {
      "type": "object",
      "properties": {
        "type": {"type": "string", "enum": ["feat", "fix", "docs", "style", "refactor", "perf", "test", "build", "ci", "chore"]},
        "subject": {"type": "string"},
        "body": {"type": "array", "items": {"type": "string"}, "maxItems": 3}
      },
      "required": ["type", "subject", "body"]
    }
    """
)

/** Ответ модели по схеме. Заголовок `type(scope): subject` из него собирает код, а не модель. */
@Serializable
class Draft(val type: String, val subject: String, val body: List<String>)

class Suggestion(val message: String, val result: ChatResult)

/**
 * Диалог с моделью об одном наборе изменений. Каждый следующий вариант просим в том же диалоге:
 * модель видит свои прошлые ответы и подсказки автора.
 */
class CommitWriter(private val ollama: OllamaClient, private val changes: StagedChanges) {
    private val messages = mutableListOf(Message("system", SYSTEM_PROMPT), Message("user", firstRequest()))

    val diffTruncated: Boolean get() = changes.diff.length > DIFF_LIMIT

    fun first(): Suggestion = ask(FIRST_TEMPERATURE)

    fun another(hint: String): Suggestion {
        val note = if (hint.isBlank()) "" else "\nNote from the author: $hint"
        messages += Message("user", "Write another commit message for the same changes, different from the previous ones.$note")
        return ask(RETRY_TEMPERATURE)
    }

    private fun ask(temperature: Double): Suggestion {
        val result = ollama.chat(messages, temperature, SCHEMA)
        messages += Message("assistant", result.content)
        val draft = try {
            Json.decodeFromString<Draft>(result.content)
        } catch (e: SerializationException) {
            throw OllamaException("Модель вернула JSON не по схеме: ${result.content}")
        }
        return Suggestion(draft.toMessage(changes.scope), result)
    }

    private fun firstRequest(): String {
        // Без этой подсказки 3B-модель называет правку README «feat».
        val docsOnly = changes.files.all { it.substringAfterLast('.').lowercase() in DOC_EXTENSIONS }
        val note = if (docsOnly) "All changed files are documentation.\n\n" else ""
        // Язык повторяем в конце: по русскому README модель иначе пишет сообщение по-русски.
        return "${note}Files changed:\n${changes.stat}\n\nDiff:\n${fitDiff(changes.diff, DIFF_LIMIT)}\n\n" +
            "Write the commit message in English."
    }
}

/**
 * Ужимает дифф до [limit] символов так, чтобы бюджет не съел первый же файл: мелкие файлы идут целиком,
 * крупные делят остаток поровну. Модель видит начало каждого файла, а не один README.
 */
private fun fitDiff(diff: String, limit: Int): String {
    if (diff.length <= limit) return diff
    val files = diff.split(Regex("(?m)^(?=diff --git )")).filter { it.isNotEmpty() }
    val budget = IntArray(files.size)
    var left = limit
    files.indices.sortedBy { files[it].length }.forEachIndexed { done, i ->
        budget[i] = minOf(files[i].length, left / (files.size - done))
        left -= budget[i]
    }
    return files.indices.joinToString("") { i ->
        val file = files[i]
        if (file.length <= budget[i]) file else file.take(budget[i]).substringBeforeLast('\n') + "\n[rest of this file's diff cut]\n"
    }
}

private val DOC_EXTENSIONS = setOf("md", "markdown", "txt", "rst", "adoc")

private fun Draft.toMessage(scope: String?): String {
    val header = if (scope == null) "$type: ${subject.normalized()}" else "$type($scope): ${subject.normalized()}"
    val lines = body.map { it.trim().removePrefix("-").trim() }.filter { it.isNotEmpty() }.distinct()
    return if (lines.isEmpty()) header else header + "\n\n" + lines.joinToString("\n") { "- $it" }
}

/** «Add support.» → «add support». Аббревиатуры не трогаем: «API …» остаётся «API …». */
private fun String.normalized(): String {
    val s = trim().removeSuffix(".")
    return if (s.length > 1 && s[0].isUpperCase() && s[1].isLowerCase()) s.replaceFirstChar { it.lowercase() } else s
}

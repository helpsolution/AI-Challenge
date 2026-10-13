package advent.review.review

import advent.review.diff.DiffLine
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import kotlin.math.abs

// Ответ модели нельзя использовать целиком — повод повторить запрос.
class InvalidOutput(message: String) : RuntimeException(message)

data class Validated(val summary: String, val accepted: List<Finding>, val rejected: List<Rejected>)

/**
 * Замечание принимается, только если указывает на строку из diff этого PR.
 * Строку ищем по дословному коду: модель часто ошибается номером на одну-две строки, но не текстом.
 */
@Component
class FindingValidator(private val json: JsonMapper) {
    fun validate(text: String, files: List<ChangedFile>, fragments: Int): Validated {
        val node = runCatching { json.readTree(text) }.getOrNull() ?: throw InvalidOutput("ответ не разобран как JSON")
        val items = node.path("findings")
        if (!items.isArray) throw InvalidOutput("в ответе нет массива findings")
        val accepted = mutableListOf<Finding>()
        val rejected = mutableListOf<Rejected>()
        (0 until items.size()).map { items.get(it) }.forEach { item ->
            when (val outcome = check(item, files, fragments)) {
                is Outcome.Accept -> {
                    val new = outcome.finding
                    if (accepted.none { it.file == new.file && it.line == new.line && it.category == new.category }) accepted += new
                }
                is Outcome.Reject -> rejected += outcome.rejected
            }
        }
        return Validated(node.path("summary").stringValue("").trim(), accepted, rejected)
    }

    private sealed interface Outcome {
        data class Accept(val finding: Finding) : Outcome
        data class Reject(val rejected: Rejected) : Outcome
    }

    private fun check(item: JsonNode, files: List<ChangedFile>, fragments: Int): Outcome {
        val title = item.path("title").stringValue("").trim().ifEmpty { "Без названия" }
        val category = Category.of(item.path("category").stringValue(""))
            ?: return Outcome.Reject(Rejected(title, "неизвестная категория «${item.path("category").stringValue("")}»"))
        val claimedFile = item.path("file").stringValue("").trim()
        val file = resolve(claimedFile, files) ?: return Outcome.Reject(Rejected(title, "файла «$claimedFile» нет среди изменённых"))
        val claimedLine = item.path("line").let { if (it.isNumber) it.intValue() else it.stringValue("").trim().toIntOrNull() }
        val line = locate(file, claimedLine, item.path("code").stringValue(""))
            ?: return Outcome.Reject(Rejected(title, "строка ${claimedLine ?: "?"} не найдена в изменённых участках ${file.diff.path}"))
        val details = item.path("details").stringValue("").trim()
        val listed = item.path("sources").let { node -> (0 until node.size()).mapNotNull { node.get(it).takeIf(JsonNode::isNumber)?.intValue() } }
        val cited = CITATION.findAll(details).map { it.groupValues[1].toInt() }
        return Outcome.Accept(Finding(
            category = category,
            severity = Severity.of(item.path("severity").stringValue("")) ?: Severity.MEDIUM,
            file = file.diff.path,
            line = line,
            title = title,
            details = details,
            suggestion = item.path("suggestion").stringValue("").trim(),
            sources = (listed + cited).filter { it in 1..fragments }.distinct().sorted(),
        ))
    }

    // Модель иногда сокращает путь до имени файла — принимаем, если совпадение однозначное.
    private fun resolve(path: String, files: List<ChangedFile>): ChangedFile? {
        val candidates = files.filter { !it.diff.binary && it.diff.visibleLines.isNotEmpty() }
        candidates.firstOrNull { it.diff.path == path }?.let { return it }
        if (path.isEmpty()) return null
        return candidates.filter { it.diff.path.endsWith("/$path") || path.endsWith("/${it.diff.path}") }.singleOrNull()
    }

    private fun locate(file: ChangedFile, claimed: Int?, code: String): Int? {
        val visible = file.diff.visibleLines
        val needle = normalize(code.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty())
        if (needle.length >= 3) {
            visible.entries.filter { normalize(it.value.text).contains(needle) }
                .minWithOrNull(compareBy({ abs(it.key - (claimed ?: it.key)) }, { it.value.kind != DiffLine.Kind.ADDED }))
                ?.let { return it.key }
        }
        // Код пересказан своими словами — доверяем номеру, если он в изменённом участке.
        return claimed?.takeIf { it in visible }
    }

    private fun normalize(text: String) = text.trim().replace(SPACES, " ")

    private companion object {
        val SPACES = Regex("\\s+")
        val CITATION = Regex("\\[(\\d+)]")
    }
}

package advent.rag.grounding

import advent.rag.agent.AnswerStatus
import advent.rag.llm.Completion
import advent.rag.reranking.RerankedChunk
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

// text — подлинный текст фрагмента, а не пересказ модели; start/end — UTF-16 offsets в content чанка.
data class Quote(val number: Int, val chunkId: String, val text: String, val start: Int, val end: Int)

// claimed — что модель выдала за цитату; found — где она нашлась во фрагменте, если нашлась.
data class QuoteCheck(val number: Int?, val claimed: String, val found: Quote?)

data class Grounding(
    val status: AnswerStatus,
    val answer: String?,
    val checks: List<QuoteCheck>,
    val references: List<Int>,
    val errors: List<String>,
) {
    val quotes: List<Quote> get() = checks.mapNotNull { it.found }
}

@Component
class GroundingChecker(private val json: JsonMapper) {
    fun check(completion: Completion, fragments: List<RerankedChunk>): Grounding {
        if (completion.finishReason == "length") return rejected("Ответ модели обрезан лимитом токенов")
        val node = runCatching { json.readTree(completion.text) }.getOrNull()
            ?: return rejected("Ответ модели не разобран как JSON")
        when (node.path("status").stringValue(null)) {
            "unknown" -> return Grounding(AnswerStatus.NO_ANSWER, null, emptyList(), emptyList(), emptyList())
            "answer" -> {}
            else -> return rejected("Поле status должно быть answer или unknown")
        }
        val answer = node.path("answer").stringValue("").trim()
        val quotes = node.path("quotes")
        val checks = if (quotes.isArray) (0 until quotes.size()).map { quote(quotes.get(it), fragments) } else emptyList()
        val references = CITATIONS.findAll(answer).flatMap { it.groupValues[1].split(SEPARATOR) }
            .map(String::toInt).distinct().sorted().toList()
        val quoted = checks.mapNotNull { it.found?.number }.toSet()
        val errors = buildList {
            if (answer.isEmpty()) add("Пустой текст ответа")
            if (checks.isEmpty()) add("В ответе нет ни одной цитаты")
            checks.filter { it.found == null }.forEach { add(problem(it, fragments.size)) }
            references.filter { it !in quoted }.forEach { add("Ссылка [$it] в ответе не подкреплена цитатой") }
        }
        return Grounding(if (errors.isEmpty()) AnswerStatus.ANSWERED else AnswerStatus.UNVERIFIED, answer, checks, references, errors)
    }

    private fun quote(node: JsonNode, fragments: List<RerankedChunk>): QuoteCheck {
        val number = node.path("fragment").takeIf(JsonNode::isInt)?.intValue()
        val claimed = node.path("quote").stringValue("")
        val chunk = number?.let { fragments.getOrNull(it - 1)?.chunk } ?: return QuoteCheck(number, claimed, null)
        val range = QuoteLocator.locate(chunk.content, claimed)
        return QuoteCheck(number, claimed, range?.let { Quote(number, chunk.chunkId, chunk.content.substring(it), it.first, it.last + 1) })
    }

    private fun problem(check: QuoteCheck, size: Int) = when {
        check.number == null || check.number !in 1..size -> "Цитата ссылается на фрагмент ${check.number ?: "без номера"}, а в контексте их 1–$size"
        else -> "Цитаты нет во фрагменте [${check.number}]: «${check.claimed.take(160)}»"
    }

    private fun rejected(error: String) = Grounding(AnswerStatus.UNVERIFIED, null, emptyList(), emptyList(), listOf(error))

    companion object {
        private val CITATIONS = Regex("\\[(\\d+(?:\\s*[,;]\\s*\\d+)*)]")
        private val SEPARATOR = Regex("\\s*[,;]\\s*")
    }
}

// Ищет цитату дословно. Прощаются только различия оформления: пробелы, регистр, ё/е, вид кавычек и тире,
// Markdown-символы * ` | # — модель пишет ответ простым текстом. Перестановки, пересказ и многоточия не проходят.
object QuoteLocator {
    fun locate(content: String, quote: String): IntRange? {
        val (text, positions) = normalize(content)
        val needle = normalize(quote).first
        if (needle.isEmpty()) return null
        val at = text.indexOf(needle).takeIf { it >= 0 } ?: return null
        return positions[at]..positions[at + needle.length - 1]
    }

    private fun normalize(source: String): Pair<String, IntArray> {
        val text = StringBuilder()
        val positions = ArrayList<Int>(source.length)
        var space = false
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c in MARKUP -> {}
                c.isWhitespace() -> space = text.isNotEmpty()
                // Перевод строки, записанный буквами «\n»: qwen экранирует его в JSON дважды, текст при этом дословный.
                c == '\\' && source.getOrNull(i + 1) in ESCAPED_BREAKS -> { space = text.isNotEmpty(); i++ }
                else -> {
                    if (space) { text.append(' '); positions += i; space = false }
                    text.append(canonical(c)); positions += i
                }
            }
            i++
        }
        return text.toString() to positions.toIntArray()
    }

    private fun canonical(c: Char): Char = when (c) {
        '«', '»', '“', '”', '„' -> '"'
        '‘', '’' -> '\''
        '—', '–', '‑', '−' -> '-'
        'ё', 'Ё' -> 'е'
        else -> c.lowercaseChar()
    }

    private const val MARKUP = "*`|#"
    private val ESCAPED_BREAKS = setOf('n', 'r', 't')
}

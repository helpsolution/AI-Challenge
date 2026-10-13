package advent.review.review

import advent.review.rag.Fragment
import advent.review.rag.location
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.util.Locale

/** Markdown комментария в PR. По первой строке-маркеру workflow находит и обновляет прежний комментарий. */
@Component
class ReviewRenderer(private val properties: ReviewProperties) {
    fun render(result: ReviewResult): String = buildString {
        appendLine(MARKER)
        appendLine("## 🤖 AI-ревью")
        appendLine()
        if (result.error != null) {
            appendLine("⚠️ **Ревью не выполнено.** Причина:")
            appendLine()
            appendLine("```")
            appendLine(result.error)
            appendLine("```")
        } else {
            result.summary?.let { appendLine("> $it"); appendLine() }
            val files = result.files
            appendLine("**Итог:** " + Category.entries.joinToString(" · ") { category ->
                "${category.emoji} ${result.findings.count { it.category == category }}"
            } + " — файлов: ${files.size}, +${files.sumOf { it.diff.added }} −${files.sumOf { it.diff.removed }}")
            Category.entries.forEach { category ->
                appendLine()
                appendLine("### ${category.emoji} ${category.title}")
                appendLine()
                val findings = result.findings.filter { it.category == category }
                if (findings.isEmpty()) appendLine("_Замечаний нет._")
                findings.forEach { appendFinding(it, result.fragments) }
            }
        }
        if (result.warnings.isNotEmpty()) {
            appendLine()
            result.warnings.forEach { appendLine("> ℹ️ $it") }
        }
        appendLine()
        appendPipeline(result)
        appendLine()
        append("<sub>Ревью сгенерировано автоматически и носит рекомендательный характер: решение о merge за человеком.</sub>")
    }

    private fun StringBuilder.appendFinding(finding: Finding, fragments: List<Fragment>) {
        appendLine("${finding.severity.emoji} **${finding.title}** · ${link(finding.file, finding.line..finding.line, short = true)}")
        appendLine()
        if (finding.details.isNotEmpty()) appendLine(finding.details)
        if (finding.suggestion.isNotEmpty()) { appendLine(); appendLine("**Как исправить:** ${finding.suggestion}") }
        if (finding.sources.isNotEmpty()) {
            appendLine()
            appendLine("<sub>📎 " + finding.sources.joinToString(" · ") { n ->
                val fragment = fragments[n - 1]
                "[$n] ${fragment.kind}: ${link(fragment.path, fragment.lines, label = fragment.location)}"
            } + "</sub>")
        }
        appendLine()
    }

    private fun StringBuilder.appendPipeline(result: ReviewResult) {
        val cost = result.llm?.takeIf { it.promptTokens > 0 }?.let { " · ≈ $" + String.format(Locale.US, "%.4f", it.cost) }.orEmpty()
        appendLine("<details>")
        appendLine("<summary>⚙️ Как работал пайплайн · ${duration(result.totalMs)}$cost</summary>")
        appendLine()
        appendLine("| Этап | Время | Результат |")
        appendLine("|---|---|---|")
        result.stages.forEach { appendLine("| ${it.name} | ${duration(it.durationMs)} | ${it.result.replace("|", "\\|")} |") }
        if (result.fragments.isNotEmpty()) {
            appendLine()
            appendLine("**Контекст RAG** (на него ссылаются [n] в замечаниях)")
            appendLine()
            result.fragments.forEachIndexed { i, fragment ->
                appendLine("${i + 1}. ${fragment.kind}: ${link(fragment.path, fragment.lines, label = fragment.location)}")
            }
        }
        if (result.rejected.isNotEmpty()) {
            appendLine()
            appendLine("**Отброшенные замечания** (не прошли проверку привязки к diff)")
            appendLine()
            result.rejected.forEach { appendLine("- «${it.title}» — ${it.reason}") }
        }
        appendLine()
        appendLine("</details>")
    }

    private fun link(path: String, lines: IntRange?, short: Boolean = false, label: String? = null): String {
        val anchor = lines?.let { if (it.first == it.last) "#L${it.first}" else "#L${it.first}-L${it.last}" }.orEmpty()
        val position = lines?.let { if (it.first == it.last) ":${it.first}" else ":${it.first}–${it.last}" }.orEmpty()
        val base = properties.linkBase.trimEnd('/')
        if (base.isEmpty()) return "`${label ?: (path + position)}`"
        val text = label ?: ((if (short) path.substringAfterLast('/') else path) + position)
        val url = base + "/" + path.split('/').joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") } + anchor
        return "[`$text`]($url)"
    }

    private fun duration(ms: Long) = if (ms == 0L) "—" else if (ms < 1000) "$ms мс" else String.format(Locale.forLanguageTag("ru"), "%.1f с", ms / 1000.0)

    companion object {
        const val MARKER = "<!-- ai-review -->"
    }
}

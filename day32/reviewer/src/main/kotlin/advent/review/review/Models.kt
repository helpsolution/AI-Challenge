package advent.review.review

import advent.review.diff.FileDiff
import advent.review.rag.Fragment

/** Файл из diff и его новая версия построчно; null — файл удалён, бинарный или не совпал с diff. */
data class ChangedFile(val diff: FileDiff, val lines: List<String>?)

enum class Category(val code: String, val title: String, val emoji: String) {
    BUG("bug", "Потенциальные баги", "🐛"),
    ARCHITECTURE("architecture", "Архитектурные проблемы", "🏗️"),
    RECOMMENDATION("recommendation", "Рекомендации", "💡");

    companion object { fun of(code: String) = entries.firstOrNull { it.code == code.trim().lowercase() } }
}

enum class Severity(val code: String, val emoji: String) {
    HIGH("high", "🔴"),
    MEDIUM("medium", "🟠"),
    LOW("low", "🟡");

    companion object { fun of(code: String) = entries.firstOrNull { it.code == code.trim().lowercase() } }
}

data class Finding(
    val category: Category,
    val severity: Severity,
    val file: String,
    val line: Int,
    val title: String,
    val details: String,
    val suggestion: String,
    val sources: List<Int>,
)

data class Rejected(val title: String, val reason: String)

data class Stage(val name: String, val durationMs: Long, val result: String)

data class LlmUsage(val model: String, val attempts: Int, val promptTokens: Int, val completionTokens: Int, val cost: Double)

data class ReviewResult(
    val files: List<ChangedFile>,
    val scopes: List<String>,
    val fragments: List<Fragment>,
    val summary: String?,
    val findings: List<Finding>,
    val rejected: List<Rejected>,
    val stages: List<Stage>,
    val llm: LlmUsage?,
    val warnings: List<String>,
    val error: String?,
    val totalMs: Long,
)

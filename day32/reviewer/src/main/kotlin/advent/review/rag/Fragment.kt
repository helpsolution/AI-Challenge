package advent.review.rag

import advent.review.chunking.Chunk

/** Пронумерованный кусок контекста, на который модель ссылается как [n]. */
sealed interface Fragment {
    val kind: String
    val path: String
    val text: String
    // Раздел документа или диапазон строк кода — для подписи и ссылки.
    val section: String?
    val lines: IntRange?
}

data class RuleFragment(val chunk: Chunk) : Fragment {
    override val kind get() = "Правила ревью"
    override val path get() = chunk.source
    override val text get() = chunk.content
    override val section get() = chunk.section
    override val lines: IntRange? get() = null
}

data class DocFragment(val chunk: Chunk, val score: Double) : Fragment {
    override val kind get() = "Документация"
    override val path get() = chunk.source
    override val text get() = chunk.content
    override val section get() = chunk.section
    override val lines: IntRange? get() = null
}

// Код из неизменённого файла, где встречается символ, объявленный или изменённый в PR.
data class UsageFragment(val chunk: CodeChunk, val symbol: String) : Fragment {
    override val kind get() = "Использование `$symbol`"
    override val path get() = chunk.path
    override val text get() = chunk.text
    override val section: String? get() = null
    override val lines get() = chunk.startLine..chunk.endLine
}

data class CodeFragment(val chunk: CodeChunk, val score: Double) : Fragment {
    override val kind get() = "Похожий код"
    override val path get() = chunk.path
    override val text get() = chunk.text
    override val section: String? get() = null
    override val lines get() = chunk.startLine..chunk.endLine
}

val Fragment.location: String
    get() = path + (section?.let { " › ${it.substringAfterLast(" › ")}" } ?: lines?.let { ":${it.first}–${it.last}" } ?: "")

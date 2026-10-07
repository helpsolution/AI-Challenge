package advent.localllm.commit

/** Изменения, по которым пишется сообщение коммита: из индекса git (CLI) или из вставленного `git diff` (веб). */
class StagedChanges(val files: List<String>, val stat: String, val diff: String) {
    /** Папка верхнего уровня, если все файлы лежат в одной: `day27/…` → `day27`. Иначе scope не ставим. */
    val scope: String? =
        files.map { it.substringBefore('/', missingDelimiterValue = "") }.distinct().singleOrNull()?.ifEmpty { null }

    /** Последняя строка `--stat`: «3 files changed, 120 insertions(+), 4 deletions(-)». */
    val summary: String get() = stat.lineSequence().last().trim()

    companion object {
        private val FILE_HEADER = Regex("""^diff --git a/(.+) b/(.+)$""")

        /** Разбирает вывод `git diff` и считает `--stat` сам: в браузере git нет. Без заголовков `diff --git` файлов не будет. */
        fun fromDiff(diff: String): StagedChanges {
            val counts = linkedMapOf<String, IntArray>()
            var current: IntArray? = null
            for (line in diff.lineSequence()) {
                val header = FILE_HEADER.find(line)
                when {
                    header != null -> current = counts.getOrPut(header.groupValues[2]) { IntArray(2) }
                    line.startsWith("+++") || line.startsWith("---") -> {}
                    line.startsWith("+") -> current?.let { it[0]++ }
                    line.startsWith("-") -> current?.let { it[1]++ }
                }
            }
            val stat = counts.map { (file, c) -> " $file | +${c[0]} -${c[1]}" } +
                " ${counts.size} files changed, ${counts.values.sumOf { it[0] }} insertions(+), ${counts.values.sumOf { it[1] }} deletions(-)"
            return StagedChanges(counts.keys.toList(), stat.joinToString("\n"), diff)
        }
    }
}

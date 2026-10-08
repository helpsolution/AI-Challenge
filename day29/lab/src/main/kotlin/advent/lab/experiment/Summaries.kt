package advent.lab.experiment

import advent.lab.task.FIELD_NAMES

fun summarize(attempts: List<Attempt>, repeats: Int, totalMs: Long): Summary {
    val perField = FIELD_NAMES.associateWith { name -> attempts.count { it.fields[name]?.ok == true } }
    // Стабильность — по тому, что модель ответила, а не по тексту: пробелы и порядок ключей не в счёт.
    val stable = if (repeats > 1) {
        attempts.groupBy { it.ticketId }.values.count { group -> group.map { answerKey(it) }.distinct().size == 1 }
    } else null
    return Summary(
        attempts = attempts.size,
        fieldsCorrect = perField.values.sum(),
        fieldsTotal = attempts.size * FIELD_NAMES.size,
        perField = perField,
        exact = attempts.count { it.correct == FIELD_NAMES.size },
        validJson = attempts.count { it.valid },
        stable = stable,
        tickets = attempts.map { it.ticketId }.distinct().size,
        latencyMedianMs = percentile(attempts.map { it.wallMs }, 0.5),
        latencyP90Ms = percentile(attempts.map { it.wallMs }, 0.9),
        generationTps = median(attempts.mapNotNull { a -> rate(a.answerTokens, a.generationMs) }),
        // Обработка промпта считается только по тем токенам, что не взяты из кэша: их Ollama и обрабатывала.
        promptTps = median(attempts.mapNotNull { a -> rate(a.promptTokens?.minus(a.cachedTokens ?: 0), a.promptMs) }),
        promptTokens = median(attempts.mapNotNull { it.promptTokens?.toDouble() })?.toInt(),
        cachedTokens = median(attempts.mapNotNull { it.cachedTokens?.toDouble() })?.toInt(),
        answerTokens = median(attempts.mapNotNull { it.answerTokens?.toDouble() })?.toInt(),
        truncated = attempts.count { it.doneReason == "length" },
        totalMs = totalMs,
    )
}

private fun answerKey(attempt: Attempt): String =
    if (!attempt.valid) "invalid" else FIELD_NAMES.joinToString("|") { attempt.fields[it]?.actual?.toString() ?: "-" }

private fun rate(tokens: Int?, ms: Long?): Double? =
    if (tokens == null || ms == null || ms <= 0 || tokens <= 0) null else tokens * 1000.0 / ms

private fun median(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
}

private fun percentile(values: List<Long>, p: Double): Long {
    if (values.isEmpty()) return 0
    val sorted = values.sorted()
    return sorted[((sorted.size - 1) * p).let { kotlin.math.ceil(it).toInt() }]
}

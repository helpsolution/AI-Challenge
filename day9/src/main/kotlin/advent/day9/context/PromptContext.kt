package advent.day9.context

import advent.day9.llm.ApiMessage

/**
 * Собранный промпт и его состав.
 *
 * Блоков недостаточно: главный вопрос дня — не «что ушло», а «сколько на чём сэкономлено»,
 * а на него отвечает только разбивка. Персона, конспект и дословный хвост меряются
 * по отдельности, потому что ведут себя по-разному: персона постоянна, конспект почти
 * постоянен, хвост ограничен — и именно поэтому запрос перестаёт расти.
 *
 * Символы, а не токены: токены считает провайдер и только после отправки, а состав
 * известен до неё.
 */
data class PromptContext(
    val blocks: List<ApiMessage>,
    val personaChars: Int,
    val summaryChars: Int,
    val tailChars: Int,
    val questionChars: Int,
    /** Сообщений переписки в промпте — дословно. */
    val promptMessages: Int,
    /** Сообщений в базе всего, включая свёрнутые. */
    val historyTotal: Int,
    /** Символов во всей переписке из базы: с чем сравнивать отправленное. */
    val historyChars: Int,
    /** Версия конспекта в промпте. null — конспекта нет: режим RAW или сворачивать нечего. */
    val summaryVersion: Int? = null,
) {
    val charsSent: Int get() = personaChars + summaryChars + tailChars + questionChars

    /** Сколько сообщений модель не видит дословно. */
    val foldedMessages: Int get() = (historyTotal - promptMessages).coerceAtLeast(0)

    /** Столько символов ушло бы в режиме RAW: персона, вся переписка и вопрос. */
    val rawChars: Int get() = personaChars + historyChars + questionChars

    /** Во сколько раз промпт меньше того, что ушло бы без сжатия. */
    val ratio: Double get() = if (charsSent > 0) rawChars.toDouble() / charsSent else 1.0
}

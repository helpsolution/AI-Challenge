package advent.rag.agent

import advent.rag.knowledge.ScoredChunk
import advent.rag.llm.ChatMessage

/**
 * Промпты агента. Базовые правила у режимов общие, иначе сравнение нечестное: RAG только
 * добавляет к ним фрагменты базы знаний и правила работы с ними — «объединение с вопросом».
 */
object Prompts {
    private const val BASE = "Ты — ассистент, который отвечает на вопросы. Отвечай по-русски, кратко и по существу. " +
        "Пиши простым текстом без Markdown: без звёздочек, решёток и таблиц; пункты списка начинай с «•»."

    private val RAG_RULES = """
        Ниже в сообщении пользователя — пронумерованные фрагменты из базы знаний, найденные по его вопросу.
        Отвечай только на их основе, не добавляй того, чего во фрагментах нет.
        После каждого утверждения ставь номер фрагмента, из которого оно взято, например [2].
        Если во фрагментах нет ответа, так и скажи: «В базе знаний нет ответа на этот вопрос».
    """.trimIndent()

    /** Без RAG модель видит только вопрос. */
    fun plain(question: String): List<ChatMessage> = listOf(
        ChatMessage("system", BASE),
        ChatMessage("user", question),
    )

    /** С RAG: фрагменты идут перед вопросом, под теми же номерами, что и источники в ответе агента. */
    fun withContext(question: String, chunks: List<ScoredChunk>): List<ChatMessage> = listOf(
        ChatMessage("system", "$BASE\n\n$RAG_RULES"),
        ChatMessage(
            "user",
            buildString {
                appendLine("Фрагменты из базы знаний:")
                chunks.forEachIndexed { i, found ->
                    appendLine()
                    appendLine("[${i + 1}] Документ «${found.chunk.title}»")
                    appendLine(found.chunk.text.trim())
                }
                appendLine()
                append("Вопрос: ").append(question)
            },
        ),
    )
}

package advent.day9.context

import advent.day9.chat.Message
import advent.day9.chat.Summary
import advent.day9.llm.ApiMessage

/**
 * Единственное место, где память превращается в контекст запроса.
 *
 * В дне 8 это были три строки внутри агента: персона, вся история, вопрос. Здесь та же
 * сборка становится предметом дня и переезжает в отдельный класс — потому что теперь
 * у неё есть решение внутри (что уходит дословно, а что пересказом) и результат, который
 * надо не только отправить, но и измерить.
 *
 * Класс без состояния и без зависимостей: одни данные внутрь, другие наружу. Проверить
 * его можно глазами по дампу промпта в логе, что и делается.
 */
class ContextAssembler(private val persona: String) {

    /**
     * Персона → конспект → дословный хвост → новый вопрос.
     *
     * Порядок не произвольный: модель читает промпт как один текст, и конспект обязан
     * стоять до сообщений, которые он предшествует по времени. Поставь его после хвоста —
     * и разговор в глазах модели пойдёт задом наперёд.
     *
     * [tail] — то, что уходит дословно: в режиме RAW это вся переписка, в режиме SUMMARY —
     * всё, что лежит за границей конспекта. Обрезкой хвоста до `keepLast` здесь никто
     * не занимается намеренно: обрезать без пересказа значит терять, а не сжимать. Хвост
     * укорачивается только сворачиванием, и происходит это после ответа.
     */
    fun assemble(
        tail: List<Message>,
        summary: Summary?,
        question: String,
        historyTotal: Int,
        historyChars: Int,
    ): PromptContext {
        val summaryBlock = summary?.let { ApiMessage("system", summaryText(it, historyTotal)) }
        val blocks = buildList {
            persona.takeIf { it.isNotBlank() }?.let { add(ApiMessage("system", it)) }
            summaryBlock?.let { add(it) }
            tail.forEach { add(ApiMessage(it.role.name.lowercase(), it.content)) }
            add(ApiMessage("user", question))
        }

        return PromptContext(
            blocks = blocks,
            personaChars = persona.takeIf { it.isNotBlank() }?.length ?: 0,
            // Меряем блок целиком, вместе с рамкой вокруг конспекта: модели уходит он,
            // а не голый текст, и платим мы за то, что ушло.
            summaryChars = summaryBlock?.content?.length ?: 0,
            tailChars = tail.sumOf { it.content.length },
            questionChars = question.length,
            promptMessages = tail.size,
            historyTotal = historyTotal,
            historyChars = historyChars,
            summaryVersion = summary?.version,
        )
    }

    /**
     * Рамка вокруг конспекта.
     *
     * Без неё модель принимает пересказ за чью-то реплику и начинает на него отвечать.
     * Числа в рамке — не украшение: они говорят модели, что часть разговора существует,
     * но дословно недоступна. Иначе на вопрос «что я говорил раньше» она уверенно
     * отвечает по тому, что видит, как будто больше ничего и не было.
     */
    private fun summaryText(summary: Summary, historyTotal: Int): String = buildString {
        appendLine(
            "Конспект начала этого разговора. Первые ${summary.coveredMessages} сообщений " +
                "из $historyTotal в запрос не попали — вместо них ты читаешь пересказ. " +
                "Считай его своей памятью: факты и договорённости из него известны тебе " +
                "так же твёрдо, как то, что сказано дальше дословно.",
        )
        appendLine()
        append(summary.content)
    }
}

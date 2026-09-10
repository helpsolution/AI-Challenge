package advent.day9.context

import advent.day9.chat.Message
import advent.day9.chat.Role
import advent.day9.chat.Summary
import advent.day9.llm.ApiMessage
import advent.day9.llm.ChatCompletionRequest
import advent.day9.llm.LlmClient
import advent.day9.llm.LlmCompletion
import advent.day9.llm.LlmException
import org.slf4j.LoggerFactory

/**
 * Сворачивает пачку сообщений в конспект — вторым обращением к модели.
 *
 * Это главный компромисс дня, и его стоит назвать прямо: сжатие не бесплатно. Чтобы
 * перестать платить за старую историю в каждом запросе, мы один раз платим за её пересказ.
 * Значит выигрыш появляется не сразу, а после того, как сэкономленное перекроет
 * потраченное, — и увидеть эту точку можно только сложив оба счёта. Поэтому обращение
 * суммаризатора попадает в ту же таблицу расхода, что и обычные ходы.
 *
 * Конспект накопительный: на вход идёт предыдущая версия и новая пачка сообщений.
 * Так размер промпта остаётся почти постоянным независимо от длины разговора — но за это
 * платят детали: то, что уже было пересказано, пересказывается снова, и на третьей-четвёртой
 * версии подробности начала разговора стираются. Все версии остаются в базе именно
 * для того, чтобы это было видно, а не только предполагалось.
 */
class Summarizer(
    private val llm: LlmClient,
    private val policy: ContextPolicy,
    private val model: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Что получилось и во что это обошлось — вместе с составом запроса, как у обычного хода. */
    data class Folded(
        val content: String,
        val completion: LlmCompletion,
        val blocks: List<ApiMessage>,
        val instructionChars: Int,
        val previousChars: Int,
        val batchChars: Int,
    ) {
        val charsSent: Int get() = blocks.sumOf { it.content.length }
    }

    /**
     * Предыдущий конспект плюс пачка сообщений → новый конспект.
     *
     * Температура низкая и не настраивается: конспект — не творчество, разброс здесь
     * означал бы, что одна и та же история сворачивается каждый раз по-разному.
     */
    fun fold(previous: Summary?, batch: List<Message>): Folded {
        require(batch.isNotEmpty()) { "Сворачивать нечего: пачка сообщений пуста" }

        val instruction = instruction()
        val task = task(previous, batch)
        val blocks = listOf(ApiMessage("system", instruction), ApiMessage("user", task))

        log.debug(
            "Сворачиваю {} сообщений в конспект версии {}. Модель: {}, потолок ответа: {} токенов",
            batch.size, (previous?.version ?: 0) + 1, model, policy.summaryMaxTokens,
        )

        val completion = llm.complete(
            ChatCompletionRequest(
                model = model,
                messages = blocks,
                temperature = SUMMARY_TEMPERATURE,
                maxTokens = policy.summaryMaxTokens,
            ),
        )

        val raw = completion.content.trim()
        if (raw.isBlank()) {
            throw LlmException("Модель вернула пустой конспект — сворачивать историю нечем")
        }

        // Обрезанный по потолку конспект кончается на полуслове — это проверено на живом
        // прогоне и стоило потерянной даты релиза: фраза «Релиз перенесён на 3 апреля»
        // оборвалась на слове «Релиз», и агент честно ответил, что даты в разговоре не было.
        //
        // Ответ при этом пришёл без ошибки, поэтому молчать здесь нельзя: обрубок уйдёт
        // модели как её собственная память. Докатываем до конца последней целой фразы —
        // потерянного не вернуть, но недосказанное лучше исковерканного.
        val content = if (completion.finishReason == "length") {
            log.warn(
                "Конспект упёрся в потолок {} токенов и обрезан на полуслове. " +
                    "Увеличьте agent.context.summary-max-tokens или уменьшите summarize-every",
                policy.summaryMaxTokens,
            )
            raw.trimToLastSentence()
        } else {
            raw
        }

        return Folded(
            content = content,
            completion = completion,
            blocks = blocks,
            instructionChars = instruction.length,
            previousChars = previous?.content?.length ?: 0,
            batchChars = batch.sumOf { it.content.length },
        )
    }

    /**
     * Инструкция конспектёра.
     *
     * Написана вокруг одного факта: этот текст — всё, что останется от свёрнутых сообщений.
     * Поэтому в ней перечислено, что терять нельзя (имена, числа, договорённости), и запрещено
     * то, чем модель охотно заполняет пересказ: вступления, оценки, пересказ реплик по очереди.
     * Мерка длины обязательна — без неё конспект растёт вместе с разговором, то есть делает
     * ровно то, от чего мы уходим.
     */
    private fun instruction(): String = """
        Ты ведёшь конспект разговора вместо его полной записи. Твой конспект — единственное,
        что останется от этих сообщений: сами они в дальнейшие запросы не попадут.

        Порядок важности — строго такой. Если места мало, жертвуй последним, а не первым:
        1. факты о собеседнике: имена, места, числа, даты, предпочтения, ограничения;
        2. решения и договорённости, включая изменённые, и открытые вопросы;
        3. темы, которые обсуждали, — одной строкой каждая, без пересказа содержания.

        Правила:
        - если факт изменился (дата сдвинулась, решение переменилось), оставляй только
          актуальное значение и помечай, что оно изменилось; устаревшее не храни;
        - объединяй в тезисы, а не пересказывай реплики по очереди;
        - пиши по-русски, от третьего лица, короткими утверждениями, без вступлений и выводов;
        - не добавляй ничего, чего не было в тексте, и не оценивай разговор;
        - если дан предыдущий конспект — включи его содержание в новый, ничего не потеряв;
        - уложись в ${policy.summaryCharBudget} символов; лучше короче, чем оборванная фраза.

        В ответе — только текст конспекта, без заголовков и пояснений.
    """.trimIndent()

    /**
     * Задание: прошлый конспект и стенограмма пачки.
     *
     * Реплики подписаны ролями, а не именами: суммаризатору не нужна ни персона агента,
     * ни его характер — за них пришлось бы платить токенами в каждом сворачивании.
     */
    private fun task(previous: Summary?, batch: List<Message>): String = buildString {
        if (previous != null) {
            appendLine("Предыдущий конспект (сообщения до этой пачки, их ${previous.coveredMessages}):")
            appendLine(previous.content)
            appendLine()
        }
        appendLine("Новые сообщения (${batch.size}), которые надо добавить в конспект:")
        batch.forEach { message ->
            val who = if (message.role == Role.USER) "Собеседник" else "Агент"
            appendLine("$who: ${message.content}")
        }
    }

    /**
     * Отрезать незаконченный хвост.
     *
     * Ищем последний знак конца фразы; если его нет вовсе или он в самом начале, оставляем
     * как есть — резать нечего, а выбрасывать единственную фразу хуже, чем оставить обрубок.
     */
    private fun String.trimToLastSentence(): String {
        val cut = indexOfLast { it in SENTENCE_END }
        return if (cut > length / 3) substring(0, cut + 1) else this
    }

    private companion object {
        /** Конспект должен быть одинаковым для одной и той же истории. */
        const val SUMMARY_TEMPERATURE = 0.2

        val SENTENCE_END = charArrayOf('.', '!', '?', ';')
    }
}

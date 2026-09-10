package advent.day9.chat

import advent.day9.context.ContextMode
import advent.day9.llm.CostSource
import java.time.Instant

/**
 * Зачем состоялось обращение к модели.
 *
 * [ANSWER] — обычный ход: вопрос пользователя, ответ агента. [SUMMARY] — сжатие истории:
 * пользователь его не видит, но платит за него ровно так же. Оба вида лежат в одной
 * таблице именно поэтому: экономия, посчитанная без учёта цены сжатия, — не экономия,
 * а подгонка. Со сжатием обращений к модели становится больше, чем ходов в диалоге.
 */
enum class TurnKind { ANSWER, SUMMARY }

/**
 * Одно обращение к модели, измеренное: что ушло, что вернулось и во что обошлось.
 *
 * Числа токенов — не оценка, а то, за что провайдер выставил счёт: своего токенизатора
 * в приложении нет и не нужно, настоящий счётчик приходит в каждом ответе.
 *
 * В этот день к измерению расхода добавляется измерение **состава**: сколько символов
 * в персоне, сколько в конспекте, сколько в дословном хвосте и сколько сообщений было
 * в базе всего. Без состава экономия остаётся числом без объяснения — непонятно, откуда
 * она взялась и что за неё отдано.
 *
 * [historyChars] нужен отдельно от [charsSent] и держит всю соль сравнения: первое — что
 * лежит в базе, второе — что реально ушло модели. В режиме RAW они почти равны, в режиме
 * SUMMARY расходятся, и по расхождению считается «сколько стоил бы этот же ход без сжатия».
 *
 * Неудачные обращения тоже попадают сюда — с [error] и без токенов.
 */
data class Turn(
    /**
     * Номер хода диалога. У обращений с [kind] = SUMMARY — номер того хода, после
     * которого сжатие случилось, поэтому номер здесь не уникален: за один ход история
     * может свернуться не один раз. Хронологию задаёт `id` в базе, а не это поле.
     */
    val number: Int,
    val kind: TurnKind,
    val at: Instant,
    /** В каком режиме собирался промпт. Хранится с каждым обращением: режим меняется рестартом. */
    val mode: ContextMode,
    val provider: String,
    val model: String,
    /** Лимит контекста модели. Включает в себя и ответ — это проверено ошибкой провайдера. */
    val contextLimit: Int,
    /** Потолок ответа. Ровно на столько лимит контекста меньше для промпта. */
    val maxTokens: Int,
    /** Сколько сообщений переписки ушло модели дословно. */
    val promptMessages: Int,
    /** Сколько сообщений лежало в базе на момент обращения — включая свёрнутые. */
    val historyTotal: Int,
    /** Символов во всей переписке из базы. Основа для ответа «а сколько было бы без сжатия». */
    val historyChars: Int,
    /** Блоков в промпте: персона + конспект + хвост + новый вопрос. */
    val promptBlocks: Int,
    /** Символов отправлено. Меряется до отправки и потому известно даже у неудачного хода. */
    val charsSent: Int,
    val personaChars: Int,
    val summaryChars: Int,
    val tailChars: Int,
    /** Версия конспекта, ушедшая в промпт. null — конспекта в промпте не было. */
    val summaryVersion: Int? = null,
    val promptTokens: Int? = null,
    val cachedPromptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val costUsd: Double? = null,
    val costSource: CostSource? = null,
    val latencyMs: Long,
    val finishReason: String? = null,
    /**
     * Признак того, что промпт до модели дошёл не целиком — **не по нашей воле**.
     *
     * Это не сжатие этого дня, а урезание на стороне агрегатора: он молча вырезает
     * середину истории, чтобы промпт влез в лимит, и отвечает `200`, как при обычном ходе.
     * Ставится по расхождению двух измерений: сколько текста добавилось к промпту (считаем
     * сами) и на сколько выросло число токенов (сообщает провайдер). Подробнее — в `Agent`.
     */
    val truncated: Boolean = false,
    /** Текст ошибки, если обращение не удалось. У удачного — null. */
    val error: String? = null,
    val errorStatus: Int? = null,
) {
    /** Сколько контекста осталось под промпт: лимит модели минус потолок ответа. */
    val contextForPrompt: Int get() = (contextLimit - maxTokens).coerceAtLeast(0)

    /**
     * Сколько символов пришлось на один токен.
     *
     * Обычно для русского текста это 2–4. Скачок на порядок означает, что провайдер
     * насчитал токенов кратно меньше, чем мы отправили символов, — то есть промпт
     * до модели дошёл урезанным.
     */
    val charsPerToken: Double? get() = promptTokens?.takeIf { it > 0 }?.let { charsSent.toDouble() / it }

    /** Сколько сообщений модель не увидела дословно: они ушли в конспект. */
    val foldedMessages: Int get() = (historyTotal - promptMessages).coerceAtLeast(0)

    /**
     * Символы, которые ушли бы модели в режиме RAW: персона, вся переписка из базы и вопрос.
     *
     * Сравнивается с [charsSent] — это и есть «до и после» в том измерении, которое
     * мы знаем точно. Токены здесь не считаются намеренно: их считает провайдер, а наша
     * оценка выглядела бы так же убедительно, как факт, не будучи фактом.
     */
    val rawChars: Int get() = personaChars + historyChars + questionChars

    /** Новый вопрос: всё, что в промпте не персона, не конспект и не хвост. */
    val questionChars: Int get() = (charsSent - personaChars - summaryChars - tailChars).coerceAtLeast(0)
}

/** Накопленный итог диалога: сумма расхода по всем обращениям к модели. */
data class Totals(
    /** Ходов диалога — то, что видел пользователь. */
    val turns: Int,
    /** Обращений к модели всего: ходы плюс сжатия. Разница и есть накладные расходы дня. */
    val requests: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val cachedPromptTokens: Int,
    val costUsd: Double?,
    /** Сколько раз история сворачивалась. */
    val summaryRequests: Int,
    /** Токены, потраченные на сжатие. Не экономия, а её цена. */
    val summaryTokens: Int,
    val summaryCostUsd: Double?,
    val failedTurns: Int,
    val truncatedTurns: Int,
)

/**
 * Итог считается из обращений, а не хранится отдельно: сумма, которую держат в поле,
 * рано или поздно разойдётся с тем, из чего её сложили.
 */
fun List<Turn>.totals(): Totals {
    val summaries = filter { it.kind == TurnKind.SUMMARY }
    return Totals(
        turns = count { it.kind == TurnKind.ANSWER },
        requests = size,
        promptTokens = sumOf { it.promptTokens ?: 0 },
        completionTokens = sumOf { it.completionTokens ?: 0 },
        totalTokens = sumOf { it.totalTokens ?: 0 },
        cachedPromptTokens = sumOf { it.cachedPromptTokens ?: 0 },
        // null, а не ноль: у DeepSeek без прайса цены нет вовсе, и это надо показать прочерком.
        costUsd = mapNotNull { it.costUsd }.takeIf { it.isNotEmpty() }?.sum(),
        summaryRequests = summaries.size,
        summaryTokens = summaries.sumOf { it.totalTokens ?: 0 },
        summaryCostUsd = summaries.mapNotNull { it.costUsd }.takeIf { it.isNotEmpty() }?.sum(),
        failedTurns = count { it.error != null },
        truncatedTurns = count { it.truncated },
    )
}

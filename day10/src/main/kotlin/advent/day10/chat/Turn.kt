package advent.day10.chat

import advent.day10.llm.CostSource
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Instant

/**
 * Один ход, измеренный: что стратегия отправила модели, что вернулось и во что обошлось.
 *
 * От дня 8 отличается тем, ради чего затеян этот день, — составом промпта. Раньше
 * в промпт уходила вся история, и мерить в ней было нечего. Теперь стратегия решает,
 * что отправить, и разница между [historyMessages] и [includedMessages] — это ровно то,
 * что стратегия отбросила.
 *
 * Числа токенов — не оценка: своего токенизатора здесь нет, это то, за что провайдер
 * выставил счёт.
 *
 * Неудачные ходы тоже попадают сюда — с [error] и без токенов.
 */
data class Turn(
    val number: Int,
    val at: Instant,
    val sessionId: Long,
    val strategy: StrategyId,
    val model: String,

    /** Сколько сообщений лежало в истории сессии на момент хода. */
    val historyMessages: Int,
    /** Сколько из них стратегия отправила дословно. */
    val includedMessages: Int,
    /** Блоков в промпте: персона + то, что собрала стратегия + новый вопрос. */
    val promptBlocks: Int,
    /** Символов отправлено. Меряется до отправки и потому известно даже у неудачного хода. */
    val charsSent: Int,
    /** Чем стратегия дополнила историю: блок фактов, метка ветки. null — ничем. */
    val note: String? = null,

    val promptTokens: Int? = null,
    val cachedPromptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val costUsd: Double? = null,
    val costSource: CostSource? = null,

    val latencyMs: Long,
    val finishReason: String? = null,
    /** Текст ошибки, если ход не удался. У удачного — null. */
    val error: String? = null,
    val errorStatus: Int? = null,
    /**
     * Что стратегия потратила сверх ответа, обновляя своё состояние.
     *
     * null — не тратила ничего: так работает скользящее окно. У стратегии фактов здесь
     * второе обращение к модели, и без этой строки она выглядела бы дешевле, чем есть.
     */
    val upkeep: Upkeep? = null,
) {
    /** Сколько сообщений истории модель на этом ходу не увидела. */
    val droppedMessages: Int get() = (historyMessages - includedMessages).coerceAtLeast(0)

    /** Токены хода целиком: ответ плюс обслуживание памяти. То, за что выставлен счёт. */
    val totalTokensWithUpkeep: Int get() = (totalTokens ?: 0) + (upkeep?.totalTokens ?: 0)
}

/** Накопленный итог сессии: сумма расхода по всем ходам. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Totals(
    val turns: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val cachedPromptTokens: Int,
    val costUsd: Double?,
    val failedTurns: Int,
    /** Сколько сообщений суммарно отброшено стратегией за весь диалог. */
    val droppedMessages: Int,
    /**
     * Расход на обслуживание памяти отдельной строкой — и намеренно не подмешанный
     * в [promptTokens] и [costUsd].
     *
     * Слить их в одну сумму значило бы спрятать то единственное, чем стратегия фактов
     * отличается от окна по деньгам. Читателю отчёта нужны оба числа: сколько стоили
     * ответы и сколько стоило помнить.
     */
    val upkeepPromptTokens: Int,
    val upkeepCompletionTokens: Int,
    val upkeepCostUsd: Double?,
    /** Сколько раз обслуживание памяти не удалось. Ход при этом состоялся. */
    val upkeepFailures: Int,
    /** Медиана задержки. Средняя врала бы: один таймаут перекашивает её целиком. */
    val medianLatencyMs: Long,
)

/**
 * Итог считается из ходов, а не хранится отдельно: сумма, которую держат в поле,
 * рано или поздно разойдётся с тем, из чего её сложили.
 */
fun List<Turn>.totals() = Totals(
    turns = size,
    promptTokens = sumOf { it.promptTokens ?: 0 },
    completionTokens = sumOf { it.completionTokens ?: 0 },
    totalTokens = sumOf { it.totalTokens ?: 0 },
    cachedPromptTokens = sumOf { it.cachedPromptTokens ?: 0 },
    // null, а не ноль: без прайса в конфиге цены нет вовсе, и это надо показать прочерком.
    costUsd = mapNotNull { it.costUsd }.takeIf { it.isNotEmpty() }?.sum(),
    failedTurns = count { it.error != null },
    droppedMessages = sumOf { it.droppedMessages },
    upkeepPromptTokens = sumOf { it.upkeep?.promptTokens ?: 0 },
    upkeepCompletionTokens = sumOf { it.upkeep?.completionTokens ?: 0 },
    upkeepCostUsd = mapNotNull { it.upkeep?.costUsd }.takeIf { it.isNotEmpty() }?.sum(),
    upkeepFailures = count { it.upkeep?.error != null },
    medianLatencyMs = map { it.latencyMs }.sorted().let { sorted ->
        if (sorted.isEmpty()) 0 else sorted[sorted.size / 2]
    },
)

/**
 * Состоявшийся ход целиком: вопрос, ответ и цена.
 *
 * Возвращается из хранилища уже с идентификаторами сообщений — они нужны интерфейсу,
 * чтобы поставить точку ветвления «после вот этой реплики».
 */
data class Exchange(
    val question: Message,
    val answer: Message,
    val turn: Turn,
)

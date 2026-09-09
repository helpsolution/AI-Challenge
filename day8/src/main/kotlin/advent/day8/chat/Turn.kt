package advent.day8.chat

import advent.day8.llm.CostSource
import java.time.Instant

/**
 * Один ход, измеренный: что ушло в модель, что вернулось и во что обошлось.
 *
 * Это главная новая сущность дня. Раньше расход существовал мгновение — попадал в лог
 * и исчезал. Теперь он лежит рядом с перепиской, и это меняет то, что можно увидеть:
 * рост токенов и цены по ходам виден не в моменте, а как история, которая переживает
 * перезапуск приложения.
 *
 * Числа токенов здесь — не оценка. Это то, за что провайдер выставил счёт: своего
 * токенизатора в приложении нет, и придумывать его не нужно, потому что настоящий
 * счётчик приходит в каждом ответе.
 *
 * Неудачные ходы тоже попадают сюда — с [error] и без токенов. День 7 такие ходы
 * не сохранял вовсе, но здесь «упёрлись в лимит» — главный экспонат, и терять его нельзя.
 */
data class Turn(
    val number: Int,
    val at: Instant,
    val provider: String,
    val model: String,
    /** Лимит контекста модели. Включает в себя и ответ — это проверено ошибкой провайдера. */
    val contextLimit: Int,
    /** Потолок ответа. Ровно на столько лимит контекста меньше для промпта. */
    val maxTokens: Int,
    /** Сколько сообщений истории пошло в промпт. */
    val historyMessages: Int,
    /** Блоков в промпте: системная инструкция + история + новый вопрос. */
    val promptBlocks: Int,
    /** Символов отправлено. Меряется до отправки и потому известно даже у неудачного хода. */
    val charsSent: Int,
    val promptTokens: Int? = null,
    val cachedPromptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val costUsd: Double? = null,
    val costSource: CostSource? = null,
    val latencyMs: Long,
    val finishReason: String? = null,
    /**
     * Признак того, что промпт до модели дошёл не целиком.
     *
     * Ставится не по догадке, а по расхождению двух измерений: сколько текста добавилось
     * к промпту (считаем сами) и на сколько выросло число токенов (сообщает провайдер).
     * Если прирост токенов заметно ниже того, во что этот текст обошёлся бы по плотности
     * прошлых ходов, — часть промпта вырезали по дороге. Подробнее — в `Agent`.
     */
    val compressed: Boolean = false,
    /** Текст ошибки, если ход не удался. У удачного — null. */
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
}

/** Накопленный итог диалога: сумма расхода по всем ходам. */
data class Totals(
    val turns: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val cachedPromptTokens: Int,
    val costUsd: Double?,
    val failedTurns: Int,
    val compressedTurns: Int,
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
    // null, а не ноль: у DeepSeek без прайса цены нет вовсе, и это надо показать прочерком.
    costUsd = mapNotNull { it.costUsd }.takeIf { it.isNotEmpty() }?.sum(),
    failedTurns = count { it.error != null },
    compressedTurns = count { it.compressed },
)

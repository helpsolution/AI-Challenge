package advent.day8.web

import advent.day8.chat.Message
import advent.day8.chat.Totals
import advent.day8.chat.Turn

/** Сообщение пользователя. */
data class AskRequest(val text: String)

/**
 * Кто отвечает и в каких границах. Настройки только на чтение: провайдер, модель и лимит
 * меняются в `application.yml` и требуют рестарта — переключателей в интерфейсе нет.
 */
data class AgentView(
    val name: String,
    val model: String,
    val contextLimit: Int,
    val maxTokens: Int,
    /** Сколько из лимита остаётся под промпт: лимит считается вместе с ответом. */
    val contextForPrompt: Int,
    /** Сколько сообщений лежит в базе — чтобы память была видна, а не только на словах. */
    val remembered: Int,
)

/** Состояние диалога при открытии страницы: и переписка, и весь измеренный расход. */
data class ChatView(
    val agent: AgentView,
    val messages: List<Message>,
    val turns: List<Turn>,
    val totals: Totals,
)

/**
 * Ответ на один ход: что сказала модель и во что это обошлось.
 *
 * Настройки агента едут вместе с ответом, а не только при загрузке страницы. Причина
 * в том, как устроен этот день: модель, лимит и режим сжатия меняются в конфиге
 * и подхватываются рестартом бэкенда. Открытая вкладка про рестарт не знает — и считала
 * бы шкалу по прежнему лимиту, показывая правдоподобное, но неверное число.
 */
data class AskResponse(
    val agent: AgentView,
    val answer: Message,
    val turn: Turn,
    val totals: Totals,
    val remembered: Int,
)

/**
 * Ошибка для интерфейса.
 *
 * Кроме своей формулировки несёт код и сырое тело ответа провайдера. В этот день это
 * не отладочная роскошь: текст ошибки о превышении контекста — то, ради чего день
 * и затевался, и показать его надо дословно, а не в пересказе.
 */
data class ErrorResponse(
    val error: String,
    val providerStatus: Int? = null,
    val providerBody: String? = null,
)

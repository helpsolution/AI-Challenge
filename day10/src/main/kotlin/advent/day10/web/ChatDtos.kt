package advent.day10.web

import advent.day10.chat.Fact
import advent.day10.chat.Message
import advent.day10.chat.Session
import advent.day10.chat.StrategyId
import advent.day10.chat.Totals
import advent.day10.chat.Turn

/** Кто отвечает и в каких границах. Настройки только на чтение: меняются в `application.yml`. */
data class AgentView(
    val name: String,
    val model: String,
    val contextLimit: Int,
    val maxTokens: Int,
    /** Сколько из лимита остаётся под промпт: лимит считается вместе с ответом. */
    val contextForPrompt: Int,
    val defaultStrategy: StrategyId,
    val defaultWindowSize: Int,
    /**
     * Стратегии, которые реально зарегистрированы.
     *
     * Список приходит из реестра, а не из перечисления: в перечислении три значения,
     * а реализованы пока не все, и предлагать в интерфейсе то, что упадёт при создании
     * сессии, — худший вид честности.
     */
    val strategies: List<StrategyId>,
)

/** Строка в списке диалогов. */
data class SessionSummary(
    val session: Session,
    val messages: Int,
    val turns: Int,
    val totals: Totals,
)

/** Диалог целиком: переписка, замеры, досье и ветки, отпочкованные от него. */
data class SessionView(
    val session: Session,
    val messages: List<Message>,
    val turns: List<Turn>,
    val totals: Totals,
    val branches: List<Session>,
    /**
     * Досье стратегии фактов. У остальных стратегий пустое.
     *
     * Показывать его обязательно: досье — это ровно то, что стратегия отправляет модели
     * вместо выброшенной истории, и пока его не видно, «агент помнит дедлайн» остаётся
     * утверждением на веру.
     */
    val facts: List<Fact>,
)

data class CreateSessionRequest(
    val title: String? = null,
    val strategy: StrategyId? = null,
    val windowSize: Int? = null,
)

/** Точка ветвления: продолжить разговор с места после указанного сообщения. */
data class ForkRequest(
    val afterMessageId: Long,
    val title: String? = null,
)

/** Сообщение пользователя. */
data class AskRequest(val text: String)

/**
 * Ответ на один ход.
 *
 * Вопрос едет обратно вместе с ответом, хотя интерфейс его и так знает: у сохранённого
 * сообщения есть идентификатор, а без него нельзя поставить на нём точку ветвления.
 */
data class AskResponse(
    val question: Message,
    val answer: Message,
    val turn: Turn,
    val totals: Totals,
)

/**
 * Ошибка для интерфейса.
 *
 * Несёт код и тело ответа провайдера: разбираться, почему прогон сценария оборвался
 * на девятой реплике, по одной нашей формулировке невозможно.
 */
data class ErrorResponse(
    val error: String,
    val providerStatus: Int? = null,
    val providerBody: String? = null,
)

package advent.day7.web

import advent.day7.chat.Message

/** Сообщение пользователя. */
data class AskRequest(val text: String)

/** Кто отвечает и что помнит. Настройки только на чтение: менять их в этот день незачем. */
data class AgentView(
    val name: String,
    val model: String,
    /** Сколько сообщений лежит в базе — чтобы память была видна, а не только на словах. */
    val remembered: Int,
)

/** Состояние диалога при открытии страницы: вся переписка приходит с сервера. */
data class ChatView(
    val agent: AgentView,
    val messages: List<Message>,
)

/** Ответ на один ход. */
data class AskResponse(
    val answer: Message,
    val remembered: Int,
)

data class ErrorResponse(val error: String)

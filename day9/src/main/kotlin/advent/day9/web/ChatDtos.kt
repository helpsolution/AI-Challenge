package advent.day9.web

import advent.day9.chat.Message
import advent.day9.chat.Summary
import advent.day9.chat.Totals
import advent.day9.chat.Turn
import advent.day9.context.ContextMode

/** Сообщение пользователя. */
data class AskRequest(val text: String)

/**
 * Кто отвечает, в каких границах и по какому правилу собирает контекст.
 *
 * Настройки только на чтение: провайдер, модель, лимит и режим сжатия меняются
 * в `application.yml` и требуют рестарта — переключателей в интерфейсе нет.
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
    /** RAW или SUMMARY. Главное, что отличает один прогон этого дня от другого. */
    val mode: ContextMode,
    val keepLast: Int,
    val summarizeEvery: Int,
    val summaryMaxTokens: Int,
    /** Какой моделью сворачивается история. Может отличаться от той, что отвечает. */
    val summaryModel: String,
)

/**
 * Состояние диалога при открытии страницы.
 *
 * Конспект едет вместе с перепиской, а не вместо неё: в базе лежит вся история, и лента
 * показывает её целиком. Конспект нужен интерфейсу для другого — отметить, какие сообщения
 * модель больше не видит дословно, и показать, во что они превратились.
 */
data class ChatView(
    val agent: AgentView,
    val messages: List<Message>,
    val turns: List<Turn>,
    val totals: Totals,
    /** Актуальный конспект. null — историю ещё не сворачивали или режим RAW. */
    val summary: Summary?,
    /** Все версии: по ним видно, как пересказ пересказа стирает подробности. */
    val summaries: List<Summary>,
)

/**
 * Ответ на один ход.
 *
 * Расход отдаётся списком целиком, а не одной записью, потому что ход может добавить
 * не одну: после ответа история могла свернуться, и это ещё одно обращение к модели
 * со своей ценой. Вкладке проще принять новое состояние, чем угадывать, что дописать.
 *
 * Настройки агента едут вместе с ответом, а не только при загрузке страницы: модель,
 * лимит и режим меняются в конфиге и подхватываются рестартом бэкенда. Открытая вкладка
 * про рестарт не знает — и считала бы шкалу по прежнему лимиту, показывая правдоподобное,
 * но неверное число.
 */
data class AskResponse(
    val agent: AgentView,
    val answer: Message,
    val turns: List<Turn>,
    val totals: Totals,
    val summary: Summary?,
    val summaries: List<Summary>,
    /** Сколько раз история свернулась на этом ходу. Обычно ноль, раз в пачку — один. */
    val folds: Int,
)

/**
 * Ошибка для интерфейса.
 *
 * Кроме своей формулировки несёт код и сырое тело ответа провайдера. Текст ошибки
 * о превышении контекста — то, ради чего затевались дни 8 и 9, и показать его надо
 * дословно, а не в пересказе.
 */
data class ErrorResponse(
    val error: String,
    val providerStatus: Int? = null,
    val providerBody: String? = null,
)

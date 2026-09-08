package advent.day7.agent

import advent.day7.chat.Message
import advent.day7.chat.Role
import advent.day7.llm.ApiMessage
import advent.day7.llm.ChatCompletionRequest
import advent.day7.llm.LlmClient
import advent.day7.llm.LlmCompletion
import advent.day7.store.MessageStore
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * Агент, который помнит разговор.
 *
 * Модель по своей природе беспамятна: она знает ровно то, что лежит в `messages` текущего
 * запроса. Поэтому «помнить» здесь — это на каждом ходу заново собрать всю переписку
 * из хранилища и отправить её вместе с новым вопросом.
 *
 * В памяти процесса истории нет: [store] — единственный источник правды. Отсюда главное
 * свойство дня: приложение можно перезапустить в любой момент, и диалог продолжится с того
 * же места, потому что терять было нечего.
 *
 * Класс не знает ни про Spring, ни про HTTP, ни про SQL — только два контракта.
 */
class Agent(
    private val llm: LlmClient,
    private val store: MessageStore,
    val settings: AgentSettings,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun history(): List<Message> = store.history()

    fun remembered(): Int = store.count()

    /**
     * Один ход: вопрос внутрь, ответ наружу.
     *
     * Порядок важен: пишем **после** ответа модели, а не до. Если модель не ответила,
     * в базе не меняется ничего — ход как будто не состоялся.
     */
    fun ask(question: String): Message {
        val text = question.trim()
        require(text.isNotEmpty()) { "Пустое сообщение — отвечать нечего" }

        val history = store.history()
        // Номер хода выводим из истории, а не храним счётчик: пара «вопрос-ответ» на ход,
        // значит следующий ход — это половина сохранённого плюс один.
        val number = history.size / 2 + 1
        val prompt = buildPrompt(history, text)

        log.info("Ход {}: сообщений в истории: {}, блоков в промпте: {}", number, history.size, prompt.size)
        log.debug("\n{}", promptDump(number, text, prompt))

        val startedNanos = System.nanoTime()
        val completion = llm.complete(
            ChatCompletionRequest(
                model = settings.model,
                messages = prompt,
                temperature = settings.temperature,
                maxTokens = settings.maxTokens,
            ),
        )
        val latencyMs = (System.nanoTime() - startedNanos) / 1_000_000

        log.debug("\n{}", answerDump(number, completion, latencyMs))

        val asked = Message(Role.USER, text, clock.instant())
        val answered = Message(Role.ASSISTANT, completion.content, clock.instant())
        store.append(asked, answered)
        log.info(
            "Ход {}: ответ за {} мс, токенов {}. Сохранено, сообщений в истории: {}",
            number, latencyMs, completion.usage?.totalTokens ?: 0, store.count(),
        )
        return answered
    }

    fun forget() {
        store.clear()
        log.info("История стёрта: {} начинает разговор с чистого листа", settings.name)
    }

    /**
     * Персона, затем вся переписка, затем новый вопрос.
     *
     * Историю отправляем целиком: задание дня — чтобы агент помнил прошлые сообщения,
     * и урезанное окно этому противоречило бы. Цена известна и осознанна: с каждым ходом
     * запрос растёт, а на очень длинном диалоге упрётся в контекст модели — тогда
     * провайдер ответит ошибкой, а не тихой потерей памяти.
     */
    private fun buildPrompt(history: List<Message>, question: String): List<ApiMessage> = buildList {
        settings.persona.takeIf { it.isNotBlank() }?.let { add(ApiMessage("system", it)) }
        history.forEach { add(ApiMessage(it.role.name.lowercase(), it.content)) }
        add(ApiMessage("user", question))
    }

    /**
     * Что пришло агенту на вход и во что он это превратил — блок за блоком, целиком.
     *
     * Промпт — главное, что стоит видеть в этом дне: именно здесь память из базы
     * превращается в контекст запроса, и именно здесь видно, как он растёт с каждым ходом.
     * Поэтому уровень DEBUG, а не TRACE: включён по умолчанию, гасится одной строкой конфига.
     */
    private fun promptDump(number: Int, input: String, prompt: List<ApiMessage>): String = buildString {
        appendLine("┌── ХОД $number · вход агента (${input.length} симв.)")
        input.lines().forEach { appendLine("│  $it") }
        appendLine("├── ПРОМПТ МОДЕЛИ · ${prompt.size} блоков, ${prompt.sumOf { it.content.length }} симв.")
        prompt.forEachIndexed { i, block ->
            appendLine("│  [${i + 1}] ${block.role.uppercase()} (${block.content.length} симв.)")
            block.content.lines().forEach { appendLine("│       $it") }
        }
        append(
            "└── уходит в ${settings.model}: temperature ${settings.temperature}, " +
                "потолок ответа ${settings.maxTokens} токенов",
        )
    }

    /** Что вернула модель: текст, расход токенов, причина остановки. */
    private fun answerDump(number: Int, completion: LlmCompletion, latencyMs: Long): String = buildString {
        appendLine("┌── ХОД $number · ответ модели за $latencyMs мс (${completion.content.length} симв.)")
        completion.content.lines().forEach { appendLine("│  $it") }
        completion.reasoning?.let { reasoning ->
            appendLine("├── скрытое рассуждение (${reasoning.length} симв.)")
            reasoning.lines().forEach { appendLine("│  $it") }
        }
        completion.usage?.let { usage ->
            val cached = usage.promptCacheHitTokens
            appendLine(
                "├── токены: вход ${usage.promptTokens}" +
                    (cached?.let { " (из них из кэша провайдера $it)" } ?: "") +
                    ", выход ${usage.completionTokens}, всего ${usage.totalTokens}",
            )
        }
        append("└── модель ${completion.model ?: settings.model}, остановка: ${completion.finishReason ?: "не указана"}")
    }
}

package advent.day10.context

import advent.day10.chat.Fact
import advent.day10.chat.Message
import advent.day10.chat.Role
import advent.day10.chat.StrategyId
import advent.day10.chat.Upkeep
import advent.day10.llm.ApiMessage
import advent.day10.llm.ChatCompletionRequest
import advent.day10.llm.LlmClient
import advent.day10.llm.ResponseFormat
import advent.day10.store.FactStore
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper

/**
 * Стратегия 2: досье фактов плюс последние N сообщений.
 *
 * Идея в том, чтобы разделить две вещи, которые скользящее окно смешивает. Дословный
 * хвост нужен для связности — чтобы агент понимал, о чём идёт речь прямо сейчас.
 * Сведения — имена, числа, сроки, решения — не обязаны храниться дословно: их можно
 * выжать в короткие пары «ключ — значение» и отправлять целиком на каждом ходу, потому
 * что досье не растёт вместе с разговором.
 *
 * Окно берётся то же самое, что у [SlidingWindowStrategy], и это принципиально: сравнение
 * должно показывать вклад досье, а не разницу в размере хвоста.
 *
 * За это платят вторым обращением к модели на каждом ходу — и платят настоящими деньгами.
 * Поэтому [observe] возвращает [Upkeep]: без него таблица сравнения показала бы стратегию
 * дешевле, чем она есть.
 *
 * **Досье собирается только из реплик пользователя.** Ответ агента в извлечение
 * не попадает намеренно: пусти его туда — и однажды выдуманная агентом деталь попадёт
 * в досье, а из досье будет уходить в каждый следующий промпт уже как факт. Ошибка,
 * которая сама себя подтверждает, — худшее, что можно сделать с памятью.
 */
class FactsStrategy(
    private val llm: LlmClient,
    private val store: FactStore,
    private val objectMapper: ObjectMapper,
    private val settings: FactsSettings,
) : ContextStrategy {

    override val id = StrategyId.FACTS

    private val log = LoggerFactory.getLogger(javaClass)

    override fun assemble(input: StrategyInput): PromptContext {
        val facts = store.facts(input.session.id)
        val window = input.history
            .takeLast(input.session.windowSize)
            // Выравнивание по реплике пользователя — по той же причине, что и в окне:
            // ответ без своего вопроса модель принимает за начало разговора.
            .dropWhile { it.role == Role.ASSISTANT }

        val blocks = buildList {
            input.persona.takeIf { it.isNotBlank() }?.let { add(ApiMessage("system", it)) }
            // Досье отдельным системным блоком, а не приклеенным к персоне: персона
            // постоянна и попадает в кэш префикса, а досье меняется почти каждый ход.
            // Слей их — и кэш ломался бы на всей персоне вместе с досье.
            facts.takeIf { it.isNotEmpty() }?.let { add(ApiMessage("system", render(it))) }
            window.forEach { add(ApiMessage(it.role.name.lowercase(), it.content)) }
            add(ApiMessage("user", input.question))
        }

        val dropped = input.history.size - window.size
        return PromptContext(
            blocks = blocks,
            includedMessages = window.size,
            note = "досье ${facts.size} " + plural(facts.size) +
                if (dropped > 0) ", окно ${input.session.windowSize}, отброшено $dropped" else "",
        )
    }

    /**
     * Обновить досье по последней реплике пользователя.
     *
     * Исключений отсюда не выпускает ни один путь: ответ модели уже получен, записан
     * и показан пользователю, и ронять ход из-за того, что не обновилось досье, нельзя.
     * Неудача возвращается как [Upkeep] с текстом ошибки и видна в интерфейсе строкой
     * «обслуживание не удалось», а не молчанием.
     */
    override fun observe(input: StrategyInput, answer: Message): Upkeep {
        val startedNanos = System.nanoTime()
        val known = store.facts(input.session.id)

        return try {
            val completion = llm.complete(
                ChatCompletionRequest(
                    model = settings.model,
                    messages = listOf(
                        ApiMessage("system", EXTRACTOR),
                        ApiMessage("user", task(known, input.question)),
                    ),
                    // Ноль, а не температура диалога: извлечение фактов — не творческая
                    // задача, и разброс формулировок здесь означал бы, что один и тот же
                    // разговор даёт разное досье от прогона к прогону.
                    temperature = 0.0,
                    maxTokens = settings.maxTokens,
                    responseFormat = ResponseFormat.JSON,
                ),
            )
            val update = parse(completion.content)
            val evicted = store.applyFacts(
                sessionId = input.session.id,
                set = update.set,
                remove = update.remove,
                turnNumber = input.turnNumber,
                limit = settings.maxFacts,
            )
            val note = note(known, update, evicted)
            log.info("Сессия {} · ход {}: {}", input.session.id, input.turnNumber, note)

            Upkeep(
                note = note,
                latencyMs = elapsedMs(startedNanos),
                promptTokens = completion.usage?.promptTokens,
                cachedPromptTokens = completion.usage?.cachedPromptTokens,
                completionTokens = completion.usage?.completionTokens,
                totalTokens = completion.usage?.totalTokens,
                costUsd = completion.usage?.costUsd,
                costSource = completion.usage?.costSource,
            )
        } catch (e: Exception) {
            log.warn(
                "Сессия {} · ход {}: досье не обновилось ({}). Ход это не ломает",
                input.session.id, input.turnNumber, e.message,
            )
            Upkeep(
                note = "досье не обновилось",
                latencyMs = elapsedMs(startedNanos),
                error = e.message ?: e::class.simpleName,
            )
        }
    }

    /** Досье как текст промпта. Порядок — как в базе: стабильный, чтобы кэш ловил префикс. */
    private fun render(facts: List<Fact>) = buildString {
        appendLine("Что известно из этого разговора (записано с его слов):")
        facts.forEach { appendLine("- ${it.key}: ${it.value}") }
        append("Опирайся на эти записи как на сказанное собеседником.")
    }

    private fun task(known: List<Fact>, question: String): String {
        val dossier = known.joinToString("\n") { "- ${it.key}: ${it.value}" }.ifEmpty { "(пусто)" }
        return """
            Текущее досье:
            $dossier

            Новая реплика собеседника:
            ${'"'}${'"'}${'"'}
            $question
            ${'"'}${'"'}${'"'}

            Верни JSON вида {"set": {"ключ": "значение"}, "remove": ["ключ"]}.
        """.trimIndent()
    }

    private fun parse(json: String): Update {
        val node = objectMapper.readTree(json)
        val set = LinkedHashMap<String, String>()
        node["set"]?.properties()?.forEach { (key, value) ->
            val name = key.trim()
            val text = value.asString().trim()
            // Пустые и обрезанные до неузнаваемости записи в досье не кладём: строка
            // «дедлайн: » хуже отсутствия строки — она выглядит как знание.
            if (name.isNotEmpty() && text.isNotEmpty()) {
                set[name] = text.take(settings.maxValueChars)
            }
        }
        val remove = node["remove"]?.mapNotNull { it.asString().trim().takeIf(String::isNotEmpty) }.orEmpty()
        return Update(set, remove)
    }

    private fun note(known: List<Fact>, update: Update, evicted: Int): String {
        val keys = known.map { it.key }.toSet()
        val added = update.set.keys.count { it !in keys }
        val changed = update.set.size - added
        val parts = buildList {
            if (added > 0) add("+$added")
            if (changed > 0) add("обновлено $changed")
            if (update.remove.isNotEmpty()) add("удалено ${update.remove.size}")
            if (evicted > 0) add("вытеснено потолком $evicted")
        }
        return "досье: " + (parts.joinToString(", ").ifEmpty { "без изменений" })
    }

    private fun plural(n: Int): String {
        val m10 = n % 10
        val m100 = n % 100
        return when {
            m10 == 1 && m100 != 11 -> "факт"
            m10 in 2..4 && m100 !in 12..14 -> "факта"
            else -> "фактов"
        }
    }

    private fun elapsedMs(startedNanos: Long) = (System.nanoTime() - startedNanos) / 1_000_000

    private data class Update(val set: Map<String, String>, val remove: List<String>)

    private companion object {
        /**
         * Инструкция извлекателю.
         *
         * Две строки в ней несут всю нагрузку. «Только то, что сказал собеседник» —
         * иначе модель начинает записывать в досье собственные выводы и рекомендации,
         * и через десять ходов там лежит её пересказ вместо требований заказчика.
         * «Ключ должен совпадать с существующим, если речь о том же» — иначе «дедлайн»
         * и «срок сдачи» заводят две записи, а досье растёт вместе с разговором,
         * то есть ровно то, от чего мы уходим.
         */
        val EXTRACTOR = """
            Ты ведёшь досье по деловому разговору: короткие записи «ключ — значение».
            В досье попадают только сведения, которые собеседник сообщил сам: имена, числа,
            сроки, ограничения, принятые решения и явные отказы.

            Правила:
            - Записывай только то, что сказано в реплике. Ничего не выводи и не додумывай.
            - Вопросы, просьбы и рассуждения собеседника — не факты. Пропускай их.
            - Ключ — короткое существительное на русском: «дедлайн», «СУБД», «каналы доставки».
            - Если реплика уточняет то, что уже есть в досье, используй ТОТ ЖЕ ключ —
              новое значение заменит старое. Не заводи синонимов.
            - Значение — кратко и по словам собеседника, вместе с важной причиной,
              если он её назвал.
            - Если реплика отменяет ранее записанное, положи ключ в "remove".
            - Если ничего нового в реплике нет, верни {"set": {}, "remove": []}.

            Ответ — только JSON: {"set": {"ключ": "значение"}, "remove": ["ключ"]}.
        """.trimIndent()
    }
}

/**
 * Настройки досье.
 *
 * [maxFacts] — единственное, что мешает стратегии выродиться в «отправляем всё». Без
 * потолка досье растёт вместе с разговором, только медленнее, и на длинной дистанции
 * приходит туда же, откуда мы уходили.
 */
data class FactsSettings(
    val model: String,
    val maxFacts: Int,
    val maxValueChars: Int,
    val maxTokens: Int,
) {
    init {
        require(maxFacts in 5..200) { "Потолок досье должен быть от 5 до 200 фактов" }
        require(maxValueChars in 40..1000) { "Значение факта — от 40 до 1000 символов" }
        require(maxTokens in 64..4096) { "Потолок ответа извлекателя — от 64 до 4096 токенов" }
    }
}

package advent.news.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Сводка по расписанию — вторая половина задания: новости сервер собирает сам,
 * а агент раз в N минут забирает накопленное и превращает его в текст для человека.
 *
 * Здесь нет agent loop: какой инструмент звать и с какими аргументами, известно заранее,
 * поэтому news_digest вызывается напрямую из кода. Модель получает готовый агрегат
 * и делает только то, что код не умеет, — группирует заголовки по смыслу.
 */
class Digester(
    private val llm: DeepSeekClient,
    private val toolbox: McpToolbox,
    private val trace: Trace,
) {
    /**
     * Где кончилась прошлая сводка. Новость может выйти в 16:29, а попасть в базу в 16:31 —
     * если резать сводки по времени публикации, она не попадёт ни в одну. Курсор по id записи
     * этого не допускает: следующая сводка берёт ровно то, что записано после предыдущей.
     * До первой сводки курсора нет, и она берёт последние N минут.
     *
     * Состояние не защищено замком само: digest зовут только под замком консоли, по одному.
     */
    private var cursor: Long? = null
    private var previousAt: Instant? = null

    suspend fun digest(minutes: Int): String {
        val arguments = buildJsonObject {
            val after = cursor
            if (after == null) put("minutes", minutes) else put("after_id", after)
            put("limit", HEADLINES)
        }
        val outcome = toolbox.call("news_digest", arguments)
        if (outcome.isError) return frame("⚠️ Сводка не собрана", outcome.text)

        val digest = outcome.structured
            ?: return frame("⚠️ Сводка не собрана", "Сервер ответил без structuredContent: ${outcome.text.take(200)}")
        val to = digest.string("to")?.let(Instant::parse) ?: Instant.now()
        val from = previousAt ?: digest.string("from")?.let(Instant::parse) ?: to.minusSeconds(minutes * 60L)
        val period = "${CLOCK.format(from)}–${CLOCK.format(to)}"
        val total = digest.int("total") ?: 0

        // Пустой период — не повод платить за запрос к модели.
        if (total == 0) {
            moveCursor(digest, to)
            return frame("📭 Новостей нет · $period", "За это время сервер новых новостей не собрал.")
        }

        val summary = try {
            val exchange = llm.complete(
                messages = listOf(
                    Message(role = "system", content = Prompts.SCHEDULED_DIGEST),
                    Message(role = "user", content = outcome.text),
                ),
                tools = emptyList(),
            )
            trace.llmExchange(exchange)
            exchange.message.content?.trim()?.takeIf { it.isNotEmpty() } ?: "Модель вернула пустую сводку."
        } catch (e: LlmException) {
            // Курсор не двигаем: эти новости войдут в следующую сводку, когда модель снова ответит.
            return frame(
                "⚠️ Сводка без пересказа · $period",
                "Модель не ответила: ${e.message}\nНовости не пропадут — они войдут в следующую сводку.\n\n" +
                    stats(digest, total),
            )
        }

        moveCursor(digest, to)
        return frame("🗞  Сводка новостей · $period", summary + "\n\n" + stats(digest, total))
    }

    private fun moveCursor(digest: JsonObject, at: Instant) {
        cursor = digest.long("cursor") ?: cursor
        previousAt = at
    }

    /** Итоговые цифры печатает код по structuredContent: пересчитывать их модели незачем, а ошибиться она может. */
    private fun stats(digest: JsonObject, total: Int): String {
        val sources = (digest["bySource"] as? JsonArray).orEmpty().map { it.jsonObject }
        val top = sources.take(TOP_SOURCES).joinToString(" · ") { "${it.string("source")} ${it.int("count")}" }
        val rest = sources.size - TOP_SOURCES
        val tail = if (rest > 0) " · ещё изданий: $rest" else ""
        val shown = digest.int("shown") ?: total
        val cut = if (shown < total) " (в сводку вошли последние $shown)" else ""
        return "📊 $total ${plural(total, "новость", "новости", "новостей")}$cut · $top$tail"
    }

    private fun frame(title: String, body: String): String =
        listOf(RULE, title, RULE, body, RULE).joinToString("\n")

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull

    private fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull

    private companion object {
        /** Сколько заголовков отдавать модели: за полчаса выходит 100–150 новостей, больше — лишние токены. */
        const val HEADLINES = 150
        const val TOP_SOURCES = 6
        val RULE = "━".repeat(56)
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    }
}

internal fun plural(n: Int, one: String, few: String, many: String): String {
    val lastTwo = n % 100
    val last = n % 10
    return when {
        lastTwo in 11..14 -> many
        last == 1 -> one
        last in 2..4 -> few
        else -> many
    }
}

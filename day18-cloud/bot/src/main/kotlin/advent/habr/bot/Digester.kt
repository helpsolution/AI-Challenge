package advent.habr.bot

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
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
 * Сводка — вторая половина задания: статьи сервер собирает сам, а бот раз в N минут забирает
 * накопленное через news_digest и превращает его в текст для человека.
 *
 * Модель получает готовый агрегат и делает только то, что код не умеет, — раскладывает статьи
 * по смыслу. Цифры и ссылки дописывает код по structuredContent.
 */
class Digester(
    private val llm: DeepSeekClient,
    private val toolbox: McpToolbox,
    private val store: CursorStore,
) {
    /**
     * Сводка по таймеру: всё, что собрано после прошлой. null — отправлять нечего: сбор выключен
     * или новых статей нет (Хабр ночью молчит, и пустые сообщения раз в два часа только мешали бы).
     */
    suspend fun scheduled(minutes: Int): String? {
        val status = toolbox.call("news_status")
        if (status.isError) return warning("Сервис статей не ответил", status.text)
        if (status.structured?.boolean("enabled") != true) return null

        val saved = store.load()
        val outcome = toolbox.call(
            "news_digest",
            buildJsonObject {
                // До первой сводки курсора нет, и она берёт последние N минут.
                if (saved == null) put("minutes", minutes) else put("after_id", saved.cursor)
                put("limit", HEADLINES)
            },
        )
        if (outcome.isError) return warning("Сводка не собрана", outcome.text)
        val digest = outcome.structured
            ?: return warning("Сводка не собрана", "MCP ответил без structuredContent: ${outcome.text.take(200)}")

        val to = digest.string("to")?.let(Instant::parse) ?: Instant.now()
        val from = saved?.at ?: digest.string("from")?.let(Instant::parse) ?: to.minusSeconds(minutes * 60L)
        val next = CursorStore.Saved(digest.long("cursor") ?: saved?.cursor ?: 0, to)

        if ((digest.int("total") ?: 0) == 0) {
            store.save(next)
            return null
        }

        val body = try {
            summarize(outcome.text, digest)
        } catch (e: LlmException) {
            // Курсор не двигаем: эти статьи войдут в следующую сводку, когда модель снова ответит.
            return warning("Модель не ответила, статьи войдут в следующую сводку", e.message.orEmpty())
        }
        store.save(next)
        return frame("🗞 Хабр · ${period(from, to)}", body, digest)
    }

    /** Сводка по команде /digest: последние N часов по времени публикации. Курсор таймера она не трогает. */
    suspend fun onDemand(hours: Int): String {
        val outcome = toolbox.call(
            "news_digest",
            buildJsonObject {
                put("minutes", hours * 60)
                put("limit", HEADLINES)
            },
        )
        if (outcome.isError) return warning("Сводка не собрана", outcome.text)
        val digest = outcome.structured
            ?: return warning("Сводка не собрана", "MCP ответил без structuredContent: ${outcome.text.take(200)}")
        if ((digest.int("total") ?: 0) == 0) return "📭 За $hours ч новых статей на Хабре нет."

        val body = try {
            summarize(outcome.text, digest)
        } catch (e: LlmException) {
            return warning("Модель не ответила", e.message.orEmpty())
        }
        return frame("🗞 Хабр за $hours ч", body, digest)
    }

    private suspend fun summarize(toolText: String, digest: JsonObject): String {
        val text = llm.complete(
            listOf(
                Message(role = "system", content = Prompts.DIGEST),
                Message(role = "user", content = toolText),
            ),
        )
        val links = (digest["articles"] as? JsonArray).orEmpty().map { it.jsonObject.string("link").orEmpty() }
        return Html.linkify(text, links)
    }

    /** Заголовок и итоговые цифры печатает код по structuredContent: пересчитывать их модели незачем, а ошибиться она может. */
    private fun frame(title: String, body: String, digest: JsonObject): String {
        val total = digest.int("total") ?: 0
        val shown = digest.int("shown") ?: total
        val cut = if (shown < total) " (в сводку вошли последние $shown)" else ""
        val tags = (digest["byCategory"] as? JsonArray).orEmpty()
            .take(TOP_TAGS)
            .joinToString(" · ") { it.jsonObject.let { tag -> "${tag.string("category")} ${tag.int("count")}" } }
        val stats = "📊 $total ${plural(total, "статья", "статьи", "статей")}$cut" +
            if (tags.isNotEmpty()) " · теги: $tags" else ""
        return "<b>${Html.escape(title)}</b>\n\n$body\n\n${Html.escape(stats)}"
    }

    private fun warning(title: String, details: String): String =
        "⚠️ <b>${Html.escape(title)}</b>\n${Html.escape(details)}"

    private fun period(from: Instant, to: Instant): String {
        val sameDay = DAY.format(from) == DAY.format(to)
        return "${(if (sameDay) CLOCK else DATE_CLOCK).format(from)}–${CLOCK.format(to)}"
    }

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull

    private fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull

    private fun JsonObject.boolean(name: String): Boolean? = this[name]?.jsonPrimitive?.booleanOrNull

    private companion object {
        /** Сколько статей отдавать модели: Хабр публикует около сотни в сутки, больше — лишние токены. */
        const val HEADLINES = 100
        const val TOP_TAGS = 5
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
        val DATE_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault())
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.systemDefault())
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

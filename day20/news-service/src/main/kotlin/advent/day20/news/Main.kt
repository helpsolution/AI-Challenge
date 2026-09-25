package advent.day20.news

import advent.day20.common.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import java.time.*

fun main() {
    val news = NewsService()
    embeddedServer(CIO, host = "127.0.0.1", port = env("NEWS_PORT", "8212").toInt()) {
        routing {
            health(); swagger("Day20 · Хабр", apiPaths(NEWS_TOOLS))
            get("/api/news") { call.api {
                val p = call.request.queryParameters
                checkInput(p["todayOnly"] == null || p["todayOnly"] in listOf("true", "false"), "todayOnly должен быть true или false")
                val limit = p["limit"]?.let { it.toIntOrNull() ?: throw ApiError(400, "limit должен быть целым числом") } ?: 10
                call.json(news.headlines(p["topic"].orEmpty(), p["mode"] ?: "mixed", p["todayOnly"] == "true", p["timezone"] ?: env("DEFAULT_TIMEZONE", "Europe/Moscow"), limit))
            } }
        }
    }.start(wait = true)
}

class NewsService {
    private val http = RemoteHttp()
    private val base = env("HABR_BASE_URL", "https://habr.com").trimEnd('/')

    suspend fun headlines(topic: String, mode: String, todayOnly: Boolean, timezone: String, limit: Int): JsonObject {
        checkInput(topic.length <= 100, "Тема не длиннее 100 символов")
        checkInput(mode in setOf("new", "popular", "mixed"), "mode: new, popular или mixed")
        checkInput(limit in 1..10, "limit от 1 до 10")
        val zone = try { ZoneId.of(timezone) } catch (e: Exception) { throw ApiError(400, "Неизвестный часовой пояс") }
        val now = Instant.now()
        val today = now.atZone(zone).toLocalDate()
        val sources = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        suspend fun fetch(popular: Boolean): List<FeedItem> {
            // RSS search ignores date=day. Popularity therefore comes from actual daily top feeds.
            val urls = when {
                popular -> listOf("$base/ru/rss/articles/top/daily/?fl=ru&limit=100", "$base/ru/rss/news/top/daily/?fl=ru&limit=100")
                topic.isNotBlank() -> listOf("$base/ru/rss/search/?q=${encode(topic.trim())}&target_type=posts&order_by=date&fl=ru&limit=100")
                else -> listOf("$base/ru/rss/articles/?fl=ru&limit=100", "$base/ru/rss/news/?fl=ru&limit=100")
            }
            val feeds = urls.map { url ->
                sources += url
                try { RssParser.parse(http.bytes(url), 100) }
                catch (e: ApiError) { warnings += "${if (popular) "Популярная" else "Новая"} лента недоступна: ${e.message}"; emptyList() }
                catch (e: FeedException) { warnings += "Хабр вернул нечитаемую RSS-ленту: $url"; emptyList() }
            }
            // Alternate articles and news: ratings of different Habr sections aren't comparable.
            return (0 until (feeds.maxOfOrNull { it.size } ?: 0)).flatMap { index -> feeds.mapNotNull { it.getOrNull(index) } }
                .distinctBy { it.id }.filter { item ->
                    val published = item.publishedAt
                    (!todayOnly || published?.atZone(zone)?.toLocalDate() == today) &&
                        (!popular || published != null && published >= now.minusSeconds(86400)) &&
                        (!popular || topic.isBlank() || matchesTopic(item, topic)) &&
                        (published == null || published <= now.plusSeconds(300))
                }
        }
        val fresh = if (mode != "popular") fetch(false).sortedByDescending { it.publishedAt } else emptyList()
        val popular = if (mode != "new") fetch(true) else emptyList()
        if (warnings.size == sources.size) throw ApiError(502, warnings.joinToString("; "))
        // Interleave rankings, retain stable source order, deduplicate by publication ID.
        val selected = when (mode) {
            "new" -> fresh
            "popular" -> popular
            else -> (0 until maxOf(fresh.size, popular.size)).flatMap { listOfNotNull(popular.getOrNull(it), fresh.getOrNull(it)) }
        }.distinctBy { it.id }.take(limit)
        return buildJsonObject {
            put("topic", topic); put("mode", mode); put("todayOnly", todayOnly)
            put("date", today.toString()); put("timezone", timezone); put("fetchedAt", now.toString())
            put("count", selected.size)
            put("items", JsonArray(selected.map { a -> buildJsonObject {
                put("id", a.id); put("title", a.title); put("url", a.link); put("excerpt", a.text.orEmpty())
                put("publishedAt", a.publishedAt?.toString()?.let(::str) ?: JsonNull)
                put("author", a.author.orEmpty()); put("tags", JsonArray(a.tags.map(::str)))
                put("selection", JsonArray(buildList { if (fresh.any { it.id == a.id }) add(str("new")); if (popular.any { it.id == a.id }) add(str("popular")) }))
            } }))
            put("sources", JsonArray(sources.map(::str))); put("warnings", JsonArray(warnings.map(::str)))
            put("selectionNote", "Хабр: статьи и новости. new — поиск по теме или свежие ленты, по дате; popular — чередование суточных топов статей и новостей, с сохранением порядка внутри каждого раздела. Тема в popular фильтруется по словам в заголовке, тегах и анонсе. mixed — чередование популярных и новых без повторов. Это подборка доступной RSS-выдачи, не полный архив или единый рейтинг всего Хабра.")
            put("contentNote", "excerpt — начало публикации, не полный текст. Если найдено меньше запрошенного количества, старые или посторонние публикации не добавляются.")
        }
    }

    private fun matchesTopic(item: FeedItem, topic: String): Boolean {
        val text = (item.title + " " + item.tags.joinToString(" ") + " " + item.text.orEmpty()).lowercase()
        val terms = topic.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        return terms.all { term -> Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(term) + "(?![\\p{L}\\p{N}])").containsMatchIn(text) }
    }
}

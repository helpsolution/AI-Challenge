package advent.day20.agent

import advent.day20.common.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class Session(val id: String, val messages: MutableList<JsonObject>, val busy: AtomicBoolean = AtomicBoolean(false))
class Job(val sessionId: String) {
    val events = CopyOnWriteArrayList<JsonObject>()
    @Volatile var result: JsonObject? = null
    @Volatile var done = false
}

fun main() {
    val sessions = ConcurrentHashMap<String, Session>()
    val jobs = ConcurrentHashMap<String, Job>()
    val directory = dataDir().resolve("chats").also { Files.createDirectories(it) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val http = RemoteHttp()
    val agent = Agent()
    fun session(id: String): Session {
        checkInput(id.matches(Regex("[a-f0-9-]{36}")), "Некорректный идентификатор чата")
        return sessions.computeIfAbsent(id) {
            val file = directory.resolve("$id.json")
            val stored = if (Files.exists(file)) JSON.parseToJsonElement(Files.readString(file)).jsonArray.map { it.jsonObject } else emptyList()
            Session(id, stored.toMutableList())
        }
    }
    fun save(s: Session) = synchronized(s) {
        val target = directory.resolve("${s.id}.json")
        val temp = directory.resolve("${s.id}.tmp")
        Files.writeString(temp, JsonArray(s.messages.takeLast(60)).toString())
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
    embeddedServer(CIO, host = "127.0.0.1", port = env("CHAT_PORT", "8210").toInt()) {
        monitor.subscribe(ApplicationStopped) { scope.cancel() }
        routing {
            health()
            get("/api/config") { call.json(buildJsonObject {
                put("defaultCity", env("DEFAULT_CITY", "Москва")); put("model", env("AGENT_MODEL", "openai/gpt-4.1-mini"))
                put("imageModel", env("IMAGE_MODEL", "bytedance-seed/seedream-4.5"))
                put("configured", env("AGENT_API_KEY", env("OPENROUTER_API_KEY")).isNotBlank())
                put("swaggerUrl", "http://localhost:${env("IMAGE_PORT", "8213")}/swagger")
            }) }
            get("/api/servers") { call.api {
                val toolbox = Toolbox()
                try { toolbox.connect(); call.json(JsonArray(toolbox.status)) } finally { toolbox.close() }
            } }
            get("/api/chats/{id}") { call.api {
                val s = session(call.parameters["id"].orEmpty())
                val list = synchronized(s) { s.messages.map { JsonObject(it.filterKeys { k -> k != "context" }) } }
                val active = jobs.entries.firstOrNull { it.value.sessionId == s.id && !it.value.done }?.key
                call.json(obj("messages" to JsonArray(list), "busy" to JsonPrimitive(s.busy.get()), "activeJob" to (active?.let(::str) ?: JsonNull)))
            } }
            post("/api/chat") { call.api {
                val body = call.bodyObject()
                val question = body.text("message").trim()
                val city = body.text("city", env("DEFAULT_CITY", "Москва")).trim()
                checkInput(question.length in 1..8000, "Сообщение: от 1 до 8000 символов")
                checkInput(city.length in 2..100, "Город: от 2 до 100 символов")
                val s = session(body.text("sessionId"))
                if (!s.busy.compareAndSet(false, true)) throw ApiError(409, "Дождитесь ответа в этом чате")
                val id = UUID.randomUUID().toString()
                val job = Job(s.id)
                if (jobs.size > 100) jobs.entries.removeIf { it.value.done }
                jobs[id] = job
                val previous = synchronized(s) {
                    val copy = s.messages.toList()
                    s.messages += obj("role" to str("user"), "text" to str(question))
                    copy
                }
                scope.launch {
                    try {
                        save(s)
                        val result = agent.run(question, city, previous) { job.events += it }
                        synchronized(s) { s.messages += result; while (s.messages.size > 60) s.messages.removeAt(0) }
                        save(s)
                        job.result = JsonObject(result.filterKeys { it != "context" })
                    } catch (e: Exception) {
                        job.result = obj("role" to str("assistant"), "text" to str("Ошибка обработки запроса: ${e.javaClass.simpleName}"), "error" to JsonPrimitive(true))
                    } finally { s.busy.set(false); job.done = true }
                }
                call.json(obj("jobId" to str(id)), 202)
            } }
            get("/api/jobs/{id}") { call.api {
                val job = jobs[call.parameters["id"]] ?: throw ApiError(404, "Запрос не найден; возможно, приложение перезапущено")
                val after = call.request.queryParameters["after"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val snapshot = job.events.toList()
                call.json(obj("done" to JsonPrimitive(job.done), "events" to JsonArray(snapshot.drop(after)), "cursor" to JsonPrimitive(snapshot.size), "result" to (job.result ?: JsonNull)))
            } }
            get("/api/images/{id}/file") { call.api {
                val id = call.parameters["id"].orEmpty()
                checkInput(id.matches(Regex("[a-f0-9-]{36}")), "Некорректный id")
                val base = env("IMAGE_API_URL", "http://127.0.0.1:${env("IMAGE_PORT", "8213")}").trimEnd('/')
                val bytes = http.bytes("$base/api/images/$id/file", maxBytes = 40_000_000)
                call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
                call.respondBytes(bytes, ContentType.Image.PNG)
            } }
            staticResources("/", "static", index = "index.html")
        }
    }.start(wait = true)
}

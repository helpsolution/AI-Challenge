package advent.day20.common

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.http.content.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
fun env(name: String, default: String = "") = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default
fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(pairs.toMap())
fun str(value: String) = JsonPrimitive(value)
fun JsonObject.text(key: String, default: String = "") = (this[key] as? JsonPrimitive)?.contentOrNull ?: default
fun JsonObject.integer(key: String, default: Int) = (this[key] as? JsonPrimitive)?.intOrNull ?: default
fun JsonObject.boolean(key: String, default: Boolean = false) = (this[key] as? JsonPrimitive)?.booleanOrNull ?: default
fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)
class ApiError(val status: Int, override val message: String) : RuntimeException(message)
fun checkInput(ok: Boolean, message: String) { if (!ok) throw ApiError(400, message) }
fun dataDir(): Path = Path.of(env("DATA_DIR", "data")).toAbsolutePath()

/** Bounded HTTP responses; credentials and provider response bodies never appear in errors. */
class RemoteHttp(private val timeoutSeconds: Long = 30) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL).build()

    suspend fun bytes(url: String, body: JsonObject? = null, key: String? = null, maxBytes: Int = 5_000_000): ByteArray =
        withContext(Dispatchers.IO) {
            val builder = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(timeoutSeconds))
                .header("User-Agent", "AI-Advent-Day20/1.0").header("Accept", "application/json, application/rss+xml")
            if (!key.isNullOrBlank()) builder.header("Authorization", "Bearer $key")
            if (body == null) builder.GET() else builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            val response = try { client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream()) }
            catch (e: Exception) { throw ApiError(502, "Не удалось связаться с ${URI(url).host}: ${e.javaClass.simpleName}") }
            response.body().use { stream ->
                if (response.statusCode() !in 200..299) {
                    val hint = when (response.statusCode()) {
                        401, 403 -> "Проверьте API-ключ и доступ к модели."
                        402 -> "Проверьте баланс OpenRouter."
                        429 -> "Превышен лимит запросов; повторите позже."
                        else -> "Проверьте доступность сервиса и параметры запроса."
                    }
                    throw ApiError(502, "${URI(url).host}: HTTP ${response.statusCode()}. $hint")
                }
                val bytes = stream.readNBytes(maxBytes + 1)
                if (bytes.size > maxBytes) throw ApiError(502, "Ответ сервиса превышает допустимый размер")
                bytes
            }
        }

    suspend fun json(url: String, body: JsonObject? = null, key: String? = null, maxBytes: Int = 5_000_000): JsonObject {
        val bytes = bytes(url, body, key, maxBytes)
        return try { JSON.parseToJsonElement(bytes.decodeToString()).jsonObject }
        catch (e: Exception) { throw ApiError(502, "${URI(url).host} вернул некорректный JSON") }
    }
}

suspend fun ApplicationCall.bodyObject(): JsonObject {
    val raw = receiveText()
    checkInput(raw.length <= 65_536, "Слишком большой запрос")
    return try { JSON.parseToJsonElement(raw).jsonObject }
    catch (e: Exception) { throw ApiError(400, "Тело запроса должно быть JSON-объектом") }
}
suspend fun ApplicationCall.json(value: JsonElement, status: Int = 200) =
    respondText(value.toString(), ContentType.Application.Json, HttpStatusCode.fromValue(status))

suspend fun ApplicationCall.api(block: suspend () -> Unit) {
    try { block() }
    catch (e: CancellationException) { throw e }
    catch (e: ApiError) { json(obj("message" to str(e.message)), e.status) }
    catch (e: IllegalArgumentException) { json(obj("message" to str(e.message ?: "Некорректные параметры")), 400) }
    catch (e: Exception) {
        application.log.error("Request failed: {}", e.javaClass.simpleName)
        json(obj("message" to str("Внутренняя ошибка сервиса: ${e.javaClass.simpleName}")), 500)
    }
}

fun Route.health() { get("/health") { call.json(obj("status" to str("ok"))) } }

/** Swagger assets are served locally from a WebJar, without CDN dependencies. */
fun Route.swagger(title: String, paths: JsonObject) {
    get("/openapi.json") {
        call.json(obj("openapi" to str("3.0.3"), "info" to obj("title" to str(title), "version" to str("1.0.0")), "paths" to paths))
    }
    // staticResources treats dots in the package name as separators, including the version.
    get("/swagger-assets/{file}") { call.api {
        val file = call.parameters["file"]
        if (file !in setOf("swagger-ui.css", "swagger-ui-bundle.js")) throw ApiError(404, "Ресурс не найден")
        val bytes = RemoteHttp::class.java.classLoader.getResourceAsStream("META-INF/resources/webjars/swagger-ui/5.32.14/$file")?.use { it.readBytes() }
            ?: throw ApiError(404, "Ресурс Swagger не найден")
        call.respondBytes(bytes, if (file!!.endsWith(".css")) ContentType.Text.CSS else ContentType.Application.JavaScript)
    } }
    get("/swagger") {
        call.respondText("""<!doctype html><html lang="ru"><head><meta charset="utf-8"><title>$title</title>
            <link rel="stylesheet" href="/swagger-assets/swagger-ui.css"></head><body><div id="swagger-ui"></div>
            <script src="/swagger-assets/swagger-ui-bundle.js"></script><script>
            SwaggerUIBundle({url:'/openapi.json',dom_id:'#swagger-ui',deepLinking:true});
            </script></body></html>""", ContentType.Text.Html)
    }
}

fun schema(type: String, description: String, vararg extra: Pair<String, JsonElement>) =
    obj("type" to str(type), "description" to str(description), *extra)
fun stringSchema(description: String, vararg values: String): JsonObject =
    if (values.isEmpty()) schema("string", description) else schema("string", description, "enum" to JsonArray(values.map(::str)))
fun objectSchema(properties: JsonObject, required: List<String> = emptyList()) =
    obj("type" to str("object"), "properties" to properties, "required" to JsonArray(required.map(::str)), "additionalProperties" to JsonPrimitive(false))
fun operation(summary: String, properties: JsonObject, post: Boolean = false, required: List<String> = emptyList()): JsonObject = buildJsonObject {
    put("summary", summary)
    if (post) put("requestBody", obj("required" to JsonPrimitive(true), "content" to obj("application/json" to obj("schema" to objectSchema(properties, required)))))
    else put("parameters", JsonArray(properties.map { (key, value) -> obj("name" to str(key), "in" to str("query"), "required" to JsonPrimitive(key in required), "schema" to value) }))
    put("responses", obj("200" to obj("description" to str("Результат")), "400" to obj("description" to str("Некорректные параметры")), "502" to obj("description" to str("Внешний сервис недоступен"))))
}

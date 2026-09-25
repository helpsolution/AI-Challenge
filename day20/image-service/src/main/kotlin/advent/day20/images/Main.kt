package advent.day20.images

import advent.day20.common.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO

fun main() {
    val images = ImageService()
    embeddedServer(CIO, host = "127.0.0.1", port = env("IMAGE_PORT", "8213").toInt()) {
        routing {
            health(); swagger("Day20 · Генерация изображений", apiPaths(IMAGE_TOOLS))
            post("/api/images") { call.api { call.json(images.generate(call.bodyObject())) } }
            get("/api/images/{id}/file") { call.api {
                val id = call.parameters["id"].orEmpty()
                checkInput(id.matches(Regex("[a-f0-9-]{36}")), "Некорректный id")
                val file = images.directory.resolve("$id.png")
                if (!Files.isRegularFile(file)) throw ApiError(404, "Изображение не найдено")
                call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
                call.respondFile(file.toFile())
            } }
        }
    }.start(wait = true)
}

class ImageService {
    val directory = dataDir().resolve("images").also { Files.createDirectories(it) }
    private val http = RemoteHttp(240)
    private val mutex = Mutex()
    suspend fun generate(args: JsonObject): JsonObject {
        validateArguments(IMAGE_TOOLS.single(), args)
        val key = env("OPENROUTER_API_KEY")
        if (key.isBlank()) throw ApiError(503, "Задайте OPENROUTER_API_KEY в day20/.env")
        val prompt = args.text("prompt").trim()
        val ratio = args.text("aspectRatio", "16:9")
        val model = env("IMAGE_MODEL", "bytedance-seed/seedream-4.5")
        return mutex.withLock {
            val result = http.json(env("OPENROUTER_BASE_URL", "https://openrouter.ai/api/v1").trimEnd('/') + "/images", buildJsonObject {
                put("model", model); put("prompt", prompt); put("aspect_ratio", ratio); put("n", 1)
            }, key, 40_000_000)
            val data = (result["data"] as? JsonArray)?.firstOrNull()?.jsonObject
                ?: throw ApiError(502, "OpenRouter не вернул изображение")
            val raw = try { Base64.getDecoder().decode(data.text("b64_json")) }
            catch (e: Exception) { throw ApiError(502, "OpenRouter вернул некорректное изображение") }
            // Decode only raster images and normalize to PNG; do not serve provider-supplied HTML/SVG.
            val image = try {
                ImageIO.createImageInputStream(raw.inputStream()).use { stream ->
                    val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull()
                        ?: throw ApiError(502, "Формат изображения не поддерживается; выберите модель PNG/JPEG")
                    try {
                        reader.input = stream
                        if (reader.getWidth(0).toLong() * reader.getHeight(0) > 32_000_000) throw ApiError(502, "Изображение слишком большое")
                        reader.read(0)
                    } finally { reader.dispose() }
                }
            } catch (e: ApiError) { throw e } catch (e: Exception) { throw ApiError(502, "Не удалось декодировать изображение") }
            val id = UUID.randomUUID().toString()
            ImageIO.write(image, "png", directory.resolve("$id.png").toFile())
            val metadata = buildJsonObject {
                put("id", id); put("prompt", prompt); put("aspectRatio", ratio); put("model", model)
                put("url", "/api/images/$id/file"); put("createdAt", Instant.now().toString())
                put("width", image.width); put("height", image.height)
                result["usage"]?.let { put("usage", it) }
            }
            Files.writeString(directory.resolve("$id.json"), metadata.toString())
            metadata
        }
    }
}

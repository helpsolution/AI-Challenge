package advent.pipeline.bot

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException

/** Ответ Bot API: всегда обёртка ok/result, причина отказа — в description. */
@Serializable
data class TelegramResponse<T>(
    val ok: Boolean,
    val result: T? = null,
    val description: String? = null,
)

@Serializable
data class Update(
    @SerialName("update_id") val updateId: Long,
    val message: TelegramMessage? = null,
)

@Serializable
data class TelegramMessage(
    @SerialName("message_id") val messageId: Long,
    val chat: Chat,
    val text: String? = null,
)

@Serializable
data class Chat(val id: Long)

class TelegramException(message: String) : RuntimeException(message)

/**
 * Клиент Telegram Bot API. Бот получает сообщения long polling-ом: сам спрашивает getUpdates и ждёт
 * до 50 секунд, пока что-то придёт. Поэтому серверу не нужны ни открытый порт, ни домен, ни TLS-сертификат,
 * как для webhook, — достаточно исходящего HTTPS.
 */
class TelegramClient(private val token: String) : AutoCloseable {
    private val base = "https://api.telegram.org/bot$token"

    private val http = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            // Запрос getUpdates висит до POLL_TIMEOUT_S секунд — таймаут клиента должен быть заметно больше.
            requestTimeoutMillis = (POLL_TIMEOUT_S + 20) * 1000L
        }
    }

    suspend fun getUpdates(offset: Long?): List<Update> = call(
        "getUpdates",
        buildJsonObject {
            offset?.let { put("offset", it) }
            put("timeout", POLL_TIMEOUT_S)
            putJsonArray("allowed_updates") { add("message") }
        },
        ListSerializer(Update.serializer()),
    )

    /**
     * Длинный текст Telegram не примет — больше 4096 символов уходит несколькими сообщениями.
     * Возвращает номер последнего из них: по нему сообщение потом можно отредактировать.
     */
    suspend fun sendMessage(chatId: Long, html: String): Long {
        var last = 0L
        for (chunk in Html.split(html, MAX_MESSAGE_LENGTH)) {
            last = call("sendMessage", message(chatId, chunk), TelegramMessage.serializer()).messageId
        }
        return last
    }

    /**
     * Заменить текст уже отправленного сообщения: так трассировка конвейера дописывается
     * в одно сообщение по мере того, как агент вызывает инструменты, а не сыплется отдельными.
     */
    suspend fun editMessage(chatId: Long, messageId: Long, html: String) {
        val body = buildJsonObject {
            message(chatId, html).forEach { (key, value) -> put(key, value) }
            put("message_id", messageId)
        }
        try {
            call("editMessageText", body, JsonElement.serializer())
        } catch (e: TelegramException) {
            // Тот же текст ещё раз Telegram отвергает — для трассировки это не ошибка.
            if (e.message?.contains("message is not modified") != true) throw e
        }
    }

    private fun message(chatId: Long, html: String): JsonObject = buildJsonObject {
        put("chat_id", chatId)
        put("text", html)
        put("parse_mode", "HTML")
        // Иначе под сводкой встанет превью первой попавшейся статьи.
        putJsonObject("link_preview_options") { put("is_disabled", true) }
    }

    /** Меню команд у поля ввода. */
    suspend fun setMyCommands(commands: List<Pair<String, String>>) {
        call(
            "setMyCommands",
            buildJsonObject {
                putJsonArray("commands") {
                    commands.forEach { (command, description) ->
                        addJsonObject {
                            put("command", command)
                            put("description", description)
                        }
                    }
                }
            },
            JsonElement.serializer(),
        )
    }

    override fun close() = http.close()

    private suspend fun <T> call(method: String, body: JsonObject, serializer: KSerializer<T>): T {
        val response = try {
            http.post("$base/$method") {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        } catch (e: IOException) {
            throw TelegramException("Telegram недоступен: ${hideToken(e.message ?: e::class.simpleName.orEmpty())}")
        }

        val text = response.bodyAsText()
        val parsed = runCatching { JSON.decodeFromString(TelegramResponse.serializer(serializer), text) }
            .getOrElse { throw TelegramException("Ответ Telegram на $method не разобран: HTTP ${response.status.value} ${text.take(200)}") }
        if (!parsed.ok) throw TelegramException("Telegram отказал в $method: ${parsed.description}")
        return parsed.result ?: throw TelegramException("Telegram ответил на $method без result")
    }

    /** Токен — часть адреса, а ошибки Ktor пишут адрес в сообщение. В лог токен попасть не должен. */
    private fun hideToken(message: String): String = message.replace(token, "<token>")

    companion object {
        const val MAX_MESSAGE_LENGTH = 4000
        private const val POLL_TIMEOUT_S = 50
        private const val CONNECT_TIMEOUT_MS = 10_000L

        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

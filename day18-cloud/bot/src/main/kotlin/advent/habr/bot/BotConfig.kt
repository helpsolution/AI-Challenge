package advent.habr.bot

import java.nio.file.Files
import java.nio.file.Path

/**
 * Настройка бота. Значения берутся из переменных окружения (на сервере их даёт systemd из EnvironmentFile),
 * а локально — из файла .env в корне day18-cloud (он в .gitignore, ключи в репозиторий не попадают).
 */
data class BotConfig(
    val telegramToken: String,
    /** Единственный чат, которому бот отвечает. null — режим настройки: бот только сообщает chat id. */
    val ownerChatId: Long?,
    val deepSeekBaseUrl: String,
    val deepSeekApiKey: String,
    val model: String,
    val mcpUrl: String,
    val mcpAuthToken: String?,
    /** Как часто бот сам присылает сводку. */
    val digestEveryMinutes: Int,
    /** Где хранить курсор сводок. На сервере это StateDirectory из юнита systemd. */
    val stateDir: Path,
) {
    companion object {
        fun load(envFile: Path = Path.of(".env")): BotConfig {
            val fileValues = readEnvFile(envFile)
            fun value(name: String): String? =
                System.getenv(name)?.takeIf { it.isNotBlank() } ?: fileValues[name]?.takeIf { it.isNotBlank() }

            fun required(name: String, hint: String): String =
                value(name) ?: error("Не задан $name. $hint Образец — .env.example.")

            val digestEvery = value("DIGEST_EVERY_MINUTES")?.let {
                it.toIntOrNull()?.takeIf { minutes -> minutes in DIGEST_MINUTES }
                    ?: error("DIGEST_EVERY_MINUTES должен быть числом от ${DIGEST_MINUTES.first} до ${DIGEST_MINUTES.last}, а не «$it»")
            } ?: DEFAULT_DIGEST_EVERY_MINUTES

            val chatId = value("TELEGRAM_CHAT_ID")?.let {
                it.toLongOrNull() ?: error("TELEGRAM_CHAT_ID должен быть числом, а не «$it»")
            }

            return BotConfig(
                telegramToken = required("TELEGRAM_BOT_TOKEN", "Токен выдаёт @BotFather."),
                ownerChatId = chatId,
                deepSeekBaseUrl = value("DEEPSEEK_BASE_URL") ?: "https://api.deepseek.com",
                deepSeekApiKey = required("DEEPSEEK_API_KEY", "Ключ — на platform.deepseek.com."),
                model = value("DEEPSEEK_MODEL") ?: "deepseek-chat",
                mcpUrl = value("MCP_URL") ?: "http://localhost:8192/mcp",
                mcpAuthToken = value("MCP_AUTH_TOKEN"),
                digestEveryMinutes = digestEvery,
                // systemd сам выставляет STATE_DIRECTORY, если в юните есть StateDirectory=
                stateDir = Path.of(System.getenv("STATE_DIRECTORY")?.takeIf { it.isNotBlank() } ?: "data"),
            )
        }

        private const val DEFAULT_DIGEST_EVERY_MINUTES = 120
        private val DIGEST_MINUTES = 1..1440

        /** Минимальный разбор .env: KEY=VALUE, пустые строки и комментарии пропускаем. */
        private fun readEnvFile(path: Path): Map<String, String> {
            if (!Files.isRegularFile(path)) return emptyMap()
            return Files.readAllLines(path)
                .mapNotNull { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
                    val separator = trimmed.indexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
                    trimmed.take(separator).trim() to trimmed.substring(separator + 1).trim().trim('"')
                }
                .toMap()
        }
    }
}

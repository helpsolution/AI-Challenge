package advent.pipeline.bot

import java.nio.file.Files
import java.nio.file.Path

/**
 * Настройка бота. Значения берутся из переменных окружения (на сервере их даёт systemd из EnvironmentFile),
 * а локально — из файла .env в корне day19 (он в .gitignore, ключи в репозиторий не попадают).
 */
data class BotConfig(
    /** null допустим только в режиме --ask: там Telegram не нужен. */
    val telegramToken: String?,
    /** Единственный чат, которому бот отвечает. null — режим настройки: бот только сообщает chat id. */
    val ownerChatId: Long?,
    val deepSeekBaseUrl: String,
    val deepSeekApiKey: String,
    val model: String,
    val mcpUrl: String,
    val mcpAuthToken: String?,
) {
    companion object {
        fun load(envFile: Path = Path.of(".env")): BotConfig {
            val fileValues = readEnvFile(envFile)
            fun value(name: String): String? =
                System.getenv(name)?.takeIf { it.isNotBlank() } ?: fileValues[name]?.takeIf { it.isNotBlank() }

            val chatId = value("TELEGRAM_CHAT_ID")?.let {
                it.toLongOrNull() ?: error("TELEGRAM_CHAT_ID должен быть числом, а не «$it»")
            }

            return BotConfig(
                telegramToken = value("TELEGRAM_BOT_TOKEN"),
                ownerChatId = chatId,
                deepSeekBaseUrl = value("DEEPSEEK_BASE_URL") ?: "https://api.deepseek.com",
                deepSeekApiKey = value("DEEPSEEK_API_KEY")
                    ?: error("Не задан DEEPSEEK_API_KEY. Ключ — на platform.deepseek.com. Образец — .env.example."),
                model = value("DEEPSEEK_MODEL") ?: "deepseek-chat",
                mcpUrl = value("MCP_URL") ?: "http://localhost:8202/mcp",
                mcpAuthToken = value("MCP_AUTH_TOKEN"),
            )
        }

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

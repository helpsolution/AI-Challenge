package advent.users.agent

import java.nio.file.Files
import java.nio.file.Path

/**
 * Настройка агента. Значения берутся из переменных окружения, а локально —
 * из файла .env рядом с проектом (он в .gitignore, ключ в репозиторий не попадает).
 */
data class AgentConfig(
    val deepSeekBaseUrl: String,
    val deepSeekApiKey: String,
    val model: String,
    val mcpUrl: String,
    val mcpAuthToken: String?,
) {
    companion object {
        fun load(envFile: Path = Path.of(".env")): AgentConfig {
            val fileValues = readEnvFile(envFile)
            fun value(name: String): String? =
                System.getenv(name)?.takeIf { it.isNotBlank() } ?: fileValues[name]?.takeIf { it.isNotBlank() }

            val apiKey = value("DEEPSEEK_API_KEY")
                ?: error(
                    "Не задан DEEPSEEK_API_KEY. Положите ключ в day17/.env " +
                        "(образец — .env.example) или в переменную окружения.",
                )

            return AgentConfig(
                deepSeekBaseUrl = value("DEEPSEEK_BASE_URL") ?: "https://api.deepseek.com",
                deepSeekApiKey = apiKey,
                model = value("DEEPSEEK_MODEL") ?: "deepseek-chat",
                mcpUrl = value("MCP_URL") ?: "http://localhost:8098/mcp",
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

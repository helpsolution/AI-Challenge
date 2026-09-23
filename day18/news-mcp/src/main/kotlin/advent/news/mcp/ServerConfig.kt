package advent.news.mcp

/**
 * Вся настройка — через переменные окружения: так сервер одинаково запускается
 * локально, в контейнере и в облаке, без правки кода и конфигов.
 */
data class ServerConfig(
    val port: Int,
    val newsApiUrl: String,
    val allowedHosts: List<String>,
    val authToken: String?,
) {
    companion object {
        const val MCP_PATH = "/mcp"

        private const val DEFAULT_PORT = 8182
        private const val DEFAULT_NEWS_API_URL = "http://localhost:8181"

        /** Значения Host, с которыми сервер работает без дополнительной настройки. */
        private val LOCALHOST = listOf("localhost", "127.0.0.1", "[::1]")

        fun fromEnvironment(): ServerConfig = ServerConfig(
            port = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_PORT,
            newsApiUrl = System.getenv("NEWS_API_URL")?.takeIf { it.isNotBlank() } ?: DEFAULT_NEWS_API_URL,
            allowedHosts = System.getenv("MCP_ALLOWED_HOSTS").toHostList() ?: LOCALHOST,
            authToken = System.getenv("MCP_AUTH_TOKEN")?.takeIf { it.isNotBlank() },
        )

        private fun String?.toHostList(): List<String>? = this
            ?.split(",")
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.takeIf { it.isNotEmpty() }
    }
}

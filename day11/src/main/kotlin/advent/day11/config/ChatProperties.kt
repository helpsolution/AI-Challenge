package advent.day11.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Где лежат диалоги. */
@ConfigurationProperties(prefix = "chat")
data class ChatProperties(
    /**
     * Файл базы. Путь относительный — от рабочей папки приложения, и намеренно не внутри
     * `build/`: иначе `./gradlew clean` уносил бы историю диалогов и память.
     */
    val dbPath: String = "./data/day11.db",
)

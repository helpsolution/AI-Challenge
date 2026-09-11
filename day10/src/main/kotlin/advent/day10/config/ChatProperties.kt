package advent.day10.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Где лежат диалоги. */
@ConfigurationProperties(prefix = "chat")
data class ChatProperties(
    /**
     * Файл базы. Путь относительный — от рабочей папки приложения, и намеренно не внутри
     * `build/`: иначе `./gradlew clean` уносил бы все прогоны вместе с их замерами.
     */
    val dbPath: String = "./data/day10.db",
)

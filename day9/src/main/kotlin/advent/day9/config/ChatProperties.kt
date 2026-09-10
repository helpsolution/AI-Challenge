package advent.day9.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Где лежит история диалога. */
@ConfigurationProperties(prefix = "chat")
data class ChatProperties(
    /**
     * Файл базы. Путь относительный — от рабочей папки приложения, и намеренно не внутри
     * `build/`: иначе `./gradlew clean` уносил бы всю переписку.
     */
    val dbPath: String = "./data/day9.db",
)

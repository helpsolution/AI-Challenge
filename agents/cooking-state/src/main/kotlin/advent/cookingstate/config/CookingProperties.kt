package advent.cookingstate.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "cooking")
data class CookingProperties(
    val dbPath: String = "./data/cooking-state.db",
    val maxMessageChars: Int = 2000,
)

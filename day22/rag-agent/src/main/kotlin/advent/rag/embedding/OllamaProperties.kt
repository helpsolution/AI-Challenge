package advent.rag.embedding

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Настройки из блока ollama в application.yml: где живёт Ollama и какой моделью считать эмбеддинги. */
@ConfigurationProperties("ollama")
data class OllamaProperties(
    val baseUrl: String,
    val model: String,
    val timeout: Duration,
    /** Префикс перед текстом чанка — так модель обучали кодировать то, по чему ищут. */
    val documentPrefix: String,
    /** Префикс перед вопросом — парный к [documentPrefix]. */
    val queryPrefix: String,
)

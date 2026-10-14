package advent.support.knowledge

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path

@ConfigurationProperties("knowledge")
data class KnowledgeProperties(
    val docsDir: Path,
    val indexFile: Path,
    val targetChars: Int,
    val maxChars: Int,
    val topK: Int,
) {
    init {
        require(targetChars > 0 && maxChars >= targetChars) { "Неверные размеры структурных чанков" }
    }
}

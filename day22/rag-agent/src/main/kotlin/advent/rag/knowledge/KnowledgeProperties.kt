package advent.rag.knowledge

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path

/** Настройки из блока knowledge в application.yml: откуда документы, куда класть базу и как резать. */
@ConfigurationProperties("knowledge")
data class KnowledgeProperties(
    val corpusDir: Path,
    val indexFile: Path,
    val chunkSize: Int,
    val overlap: Int,
)

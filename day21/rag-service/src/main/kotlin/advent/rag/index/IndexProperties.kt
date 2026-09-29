package advent.rag.index

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path

/** Настройки из блока index в application.yml: откуда брать документы и куда класть базы индекса. */
@ConfigurationProperties("index")
data class IndexProperties(
    val corpusDir: Path,
    val dir: Path,
)

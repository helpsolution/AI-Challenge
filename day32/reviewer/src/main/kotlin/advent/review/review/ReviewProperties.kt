package advent.review.review

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path

@ConfigurationProperties("review")
data class ReviewProperties(
    val root: Path,
    val diff: Path,
    val output: Path,
    val linkBase: String,
    val rules: String,
    val docsLimit: Int,
    val codeLimit: Int,
    val usagesLimit: Int,
    val fullFileLines: Int,
    val llmAttempts: Int,
)

package advent.review

import advent.review.review.ReviewPipeline
import advent.review.review.ReviewProperties
import advent.review.review.ReviewRenderer
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import java.nio.file.Files
import kotlin.system.exitProcess

@SpringBootApplication
@ConfigurationPropertiesScan
class ReviewerApplication

/** Читает diff, пишет Markdown ревью. Код выхода 1 — ревью не выполнено, но файл с причиной всё равно записан. */
fun main(args: Array<String>) {
    val code = runApplication<ReviewerApplication>(*args).use { context ->
        val properties = context.getBean(ReviewProperties::class.java)
        val result = context.getBean(ReviewPipeline::class.java).review(Files.readString(properties.diff))
        properties.output.toAbsolutePath().parent?.let(Files::createDirectories)
        Files.writeString(properties.output, context.getBean(ReviewRenderer::class.java).render(result) + "\n")
        val log = LoggerFactory.getLogger(ReviewerApplication::class.java)
        log.info("Ревью записано в {} за {} мс: замечаний {}, отброшено {}", properties.output, result.totalMs, result.findings.size, result.rejected.size)
        result.error?.let { log.error("Ревью не выполнено: {}", it) }
        if (result.error == null) 0 else 1
    }
    exitProcess(code)
}

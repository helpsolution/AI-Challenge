package advent.rag.web

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.nio.file.Path

@ConfigurationProperties("evaluation")
data class EvaluationProperties(val runFile: Path)

// Результат последнего прогона tools/eval_compare.py — для страницы «Замеры». Файл пишет скрипт, сервер только читает.
@RestController
class EvaluationController(private val properties: EvaluationProperties) {
    @GetMapping("/api/evaluation")
    fun latest(): ResponseEntity<String> {
        if (!Files.exists(properties.runFile)) {
            throw NoSuchElementException("Прогона ещё не было: запустите python3 tools/eval_compare.py")
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(Files.readString(properties.runFile))
    }
}

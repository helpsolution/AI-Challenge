package advent.lab.experiment

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

@ConfigurationProperties("lab")
data class LabProperties(val experimentsDir: Path)

/** Строка журнала: всё, кроме попыток, — их загружает сравнение по id. */
data class ExperimentHead(
    val id: String, val name: String, val createdAt: java.time.Instant, val set: String, val repeats: Int,
    val config: RunConfig, val resources: Resources, val summary: Summary,
)

// Журнал экспериментов: по JSON-файлу на прогон в data/experiments. Файлы можно сравнивать и после перезапуска.
@Component
class ExperimentStore(private val json: JsonMapper, properties: LabProperties) {
    private val dir: Path = properties.experimentsDir

    fun save(experiment: Experiment) {
        Files.createDirectories(dir)
        file(experiment.id).writeText(json.writerWithDefaultPrettyPrinter().writeValueAsString(experiment))
    }

    fun list(): List<ExperimentHead> {
        if (!Files.isDirectory(dir)) return emptyList()
        return dir.listDirectoryEntries("*.json").map { json.readValue(it.readText(), Experiment::class.java) }
            .sortedBy { it.createdAt }
            .map { ExperimentHead(it.id, it.name, it.createdAt, it.set, it.repeats, it.config, it.resources, it.summary) }
    }

    fun get(id: String): Experiment {
        val file = file(id)
        if (!file.isRegularFile()) throw NoSuchElementException("Нет эксперимента $id")
        return json.readValue(file.readText(), Experiment::class.java)
    }

    fun delete(id: String) {
        file(id).deleteIfExists()
    }

    private fun file(id: String): Path {
        require(ID.matches(id)) { "Некорректный id эксперимента: $id" }
        return dir.resolve("$id.json")
    }

    companion object {
        val ID = Regex("[0-9]{8}-[0-9]{6}")
    }
}

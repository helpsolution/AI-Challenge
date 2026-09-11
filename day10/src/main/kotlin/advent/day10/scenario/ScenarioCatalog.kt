package advent.day10.scenario

import org.slf4j.LoggerFactory
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Сценарии из JSON-файлов в `resources/scenario`.
 *
 * Файлы, а не код: сценарий — это данные эксперимента, и править его должно быть можно,
 * не пересобирая приложение. Читаются один раз на старте — прогон не должен зависеть
 * от того, не подменили ли файл на середине.
 */
@Component
class ScenarioCatalog(objectMapper: ObjectMapper) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val byId: Map<String, Scenario> = PathMatchingResourcePatternResolver()
        .getResources("classpath*:scenario/*.json")
        .map { resource -> resource.inputStream.use { objectMapper.readValue(it, Scenario::class.java) } }
        .associateBy { it.id }
        .also { log.info("Сценариев загружено: {} ({})", it.size, it.keys.joinToString()) }

    fun all(): List<Scenario> = byId.values.sortedBy { it.id }

    fun require(id: String): Scenario = byId[id]
        ?: throw IllegalArgumentException("Сценарий «$id» не найден. Есть: ${byId.keys.joinToString()}")
}

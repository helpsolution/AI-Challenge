package advent.day10.web

import advent.day10.chat.Session
import advent.day10.chat.StrategyId
import advent.day10.scenario.Scenario
import advent.day10.scenario.ScenarioCatalog
import advent.day10.scenario.ScenarioRun
import advent.day10.scenario.ScenarioRunner
import advent.day10.store.ChatStore
import advent.day10.store.ScenarioRunStore
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/scenarios")
class ScenarioController(
    private val catalog: ScenarioCatalog,
    private val runner: ScenarioRunner,
    private val runs: ScenarioRunStore,
    private val store: ChatStore,
) {

    @GetMapping
    fun scenarios(): List<Scenario> = catalog.all()

    /**
     * Запустить прогон. Возвращается сразу: сама работа — два десятка обращений к модели
     * и идёт минутами, держать под это открытый запрос незачем.
     */
    @PostMapping("/{scenarioId}/runs")
    fun start(@PathVariable scenarioId: String, @RequestBody request: StartRunRequest): StartedRun {
        val session = runner.start(scenarioId, request.strategy, request.windowSize)
        return StartedRun(session, runner.size(scenarioId))
    }

    /** Все завершённые прогоны — из них строится таблица сравнения. */
    @GetMapping("/runs")
    fun runs(): List<ScenarioRun> = runs.all()

    /**
     * Как идёт прогон.
     *
     * Отдельного реестра прогресса нет: сделанное и так лежит в базе. Выполненные
     * обращения — это ходы основной сессии плюс ходы веток, в которых задавались
     * контрольные вопросы. Прогон закончен тогда, когда появился отчёт.
     */
    @GetMapping("/runs/{sessionId}/progress")
    fun progress(@PathVariable sessionId: Long): RunProgress {
        val done = (listOf(sessionId) + store.branches(sessionId).map { it.id })
            .sumOf { store.turns(it).size }
        return RunProgress(sessionId, done, runs.bySession(sessionId))
    }
}

data class StartRunRequest(
    val strategy: StrategyId,
    val windowSize: Int? = null,
)

/** Прогон запущен: за этой сессией и надо следить. [total] — сколько будет обращений к модели. */
data class StartedRun(val session: Session, val total: Int)

/** [run] не null — прогон закончен, и в нём же лежит причина, если он оборвался. */
data class RunProgress(
    val sessionId: Long,
    val completed: Int,
    val run: ScenarioRun?,
)

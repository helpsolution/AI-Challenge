package advent.day10.scenario

import advent.day10.agent.Agent
import advent.day10.chat.Session
import advent.day10.chat.StrategyId
import advent.day10.chat.totals
import advent.day10.store.ChatStore
import advent.day10.store.ScenarioRunStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock

/**
 * Прогон сценария на одной стратегии.
 *
 * Работает в фоне: пятнадцать реплик и восемь проверок — это двадцать три обращения
 * к модели, то есть минуты. Держать всё это внутри HTTP-запроса означало бы показывать
 * пользователю пустой экран без единого признака жизни. Поэтому [start] возвращает
 * сессию сразу, а ход прогона виден по тому, что в ней уже записано: ходы и ветки
 * появляются в базе по мере выполнения, и опрашивать какой-то отдельный реестр прогресса
 * не нужно.
 */
@Service
class ScenarioRunner(
    private val agent: Agent,
    private val store: ChatStore,
    private val runs: ScenarioRunStore,
    private val catalog: ScenarioCatalog,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Сколько обращений к модели займёт прогон: реплики плюс контрольные вопросы. */
    fun size(scenarioId: String): Int = catalog.require(scenarioId).let { it.steps.size + it.checks.size }

    /**
     * Создать сессию и запустить прогон в фоне.
     *
     * Сессия создаётся синхронно и возвращается сразу — иначе клиенту не за чем было бы
     * следить.
     */
    fun start(scenarioId: String, strategy: StrategyId, windowSize: Int?): Session {
        val scenario = catalog.require(scenarioId)
        val session = agent.createSession(
            title = "${scenario.title} · ${title(strategy)}",
            strategy = strategy,
            windowSize = windowSize,
        )
        // Виртуальный поток: прогон почти всё время ждёт модель, и держать под это
        // платформенный поток незачем.
        Thread.ofVirtual().name("scenario-${session.id}").start { run(scenario, session) }
        return session
    }

    private fun run(scenario: Scenario, session: Session) {
        val startedAt = clock.instant()
        log.info(
            "Прогон «{}» на {} (окно {}), сессия {}: {} реплик, {} проверок",
            scenario.id, session.strategy, session.windowSize, session.id,
            scenario.steps.size, scenario.checks.size,
        )

        val checks = mutableListOf<CheckResult>()
        var failure: String? = null
        try {
            scenario.steps.forEachIndexed { i, step ->
                log.info("Прогон {}: реплика {}/{}", session.id, i + 1, scenario.steps.size)
                agent.ask(session.id, step)
            }
            scenario.checks.forEach { checks += probe(session, it) }
        } catch (e: Exception) {
            failure = e.message ?: e::class.simpleName
            log.warn("Прогон {} прерван: {}", session.id, failure)
        }

        // Расход сценария и расход измерения считаются раздельно.
        //
        // Сравнимо между стратегиями только первое: пятнадцать реплик, одинаковый вход,
        // одинаковое число ходов. Контрольные вопросы — инструмент, и для стратегии
        // фактов каждый из них стоит вдвое: ответ плюс обновление досье в ветке, которую
        // тут же выбрасывают. Свали их в одну сумму — и факты выглядели бы дороже окна
        // на восемь вызовов, которых в настоящей работе агента нет.
        val scenarioTurns = store.turns(session.id)
        val probeTurns = store.branches(session.id).flatMap { store.turns(it.id) }

        val run = ScenarioRun(
            id = 0,
            scenarioId = scenario.id,
            scenarioTitle = scenario.title,
            strategy = session.strategy,
            windowSize = session.windowSize,
            sessionId = session.id,
            startedAt = startedAt,
            finishedAt = clock.instant(),
            totals = scenarioTurns.totals(),
            probeTotals = probeTurns.totals(),
            checks = checks,
            error = failure,
        )
        runs.save(run)
        log.info(
            "Прогон {} завершён: проверок пройдено {}/{}, токенов на сценарий {} (из них на память {}), " +
                "ранняя запомненная деталь — шаг {}",
            session.id, run.passed, run.checks.size, run.totals.totalTokens,
            run.totals.upkeepPromptTokens + run.totals.upkeepCompletionTokens,
            run.earliestRemembered ?: "нет",
        )
    }

    /**
     * Один контрольный вопрос — в собственной ветке от конца диалога.
     *
     * Иначе измерение сломалось бы: восемь вопросов подряд в одной сессии заполнили бы
     * окно ответами на предыдущие вопросы, и к последней проверке сам сценарий вытеснился
     * бы из контекста целиком. Провалились бы все поздние проверки — не потому, что
     * стратегия плоха, а потому что мы сами затолкали их за границу окна.
     *
     * Ветка даёт каждому вопросу один и тот же контекст: ровно тот, каким диалог
     * закончился. Заодно это первое настоящее применение форка — тот же механизм,
     * на котором построена стратегия ветвления.
     */
    private fun probe(session: Session, check: Check): CheckResult {
        val last = store.history(session.id).lastOrNull()
            ?: throw IllegalStateException("Сценарий не оставил ни одного сообщения — проверять нечего")
        val branch = store.fork(session.id, last.id, "проверка «${check.id}» · шаг ${check.plantedAtStep}")
        val answer = agent.ask(branch.id, check.question).answer.content
        return evaluate(check, answer, branch.id)
    }

    private fun evaluate(check: Check, answer: String, sessionId: Long): CheckResult {
        val normalized = normalize(answer)
        val missing = check.expect.filterNot { normalize(it) in normalized }
        val hallucinated = check.forbid.filter { normalize(it) in normalized }
        return CheckResult(
            id = check.id,
            plantedAtStep = check.plantedAtStep,
            question = check.question,
            answer = answer,
            passed = missing.isEmpty() && hallucinated.isEmpty(),
            missing = missing,
            hallucinated = hallucinated,
            sessionId = sessionId,
        )
    }

    /**
     * Приведение к сравнимому виду: регистр и «ё».
     *
     * «Ковалёва» и «Ковалева» — одно и то же имя, и модель пишет то так, то так.
     * Провалить проверку памяти из-за типографики значило бы измерить не то, что хотели.
     */
    private fun normalize(text: String) = text.lowercase().replace('ё', 'е')

    private fun title(strategy: StrategyId) = when (strategy) {
        StrategyId.SLIDING_WINDOW -> "окно"
        StrategyId.FACTS -> "факты"
        StrategyId.BRANCHING -> "ветвление"
    }
}

package advent.day10.config

import advent.day10.agent.Agent
import advent.day10.chat.StrategyId
import advent.day10.context.FactsSettings
import advent.day10.context.FactsStrategy
import advent.day10.context.SlidingWindowStrategy
import advent.day10.context.StrategyRegistry
import advent.day10.llm.DeepSeekClient
import advent.day10.llm.LlmClient
import advent.day10.store.ChatStore
import advent.day10.store.FactStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.time.Clock

@Configuration
class AgentConfiguration {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    /** Провайдер один, выбирать не из чего — клиент создаётся напрямую, без фабрики по имени. */
    @Bean
    fun llmClient(properties: DeepSeekProperties, objectMapper: ObjectMapper): LlmClient =
        DeepSeekClient(properties, objectMapper)

    /**
     * Скользящее окно регистрируется дважды — под своим идентификатором и под
     * `BRANCHING`.
     *
     * Это не заглушка. Ветвление не является способом собрать промпт: оно отвечает
     * на вопрос, какая история считается текущей, и живёт в хранилище как форк сессии.
     * Сборка промпта в ветке остаётся ровно той же. Отдельный класс здесь только
     * повторял бы окно слово в слово — и первое же изменение окна пришлось бы вносить
     * в два места.
     */
    @Bean
    fun strategyRegistry(
        llm: LlmClient,
        facts: FactStore,
        objectMapper: ObjectMapper,
        properties: AgentProperties,
    ) = StrategyRegistry(
        listOf(
            SlidingWindowStrategy(),
            SlidingWindowStrategy(StrategyId.BRANCHING),
            FactsStrategy(
                llm = llm,
                store = facts,
                objectMapper = objectMapper,
                settings = with(properties.context.facts) {
                    FactsSettings(
                        // Пустая строка в конфиге означает «той же моделью, что отвечает».
                        // Отдельная модель для извлечения — осознанный выбор, а не умолчание:
                        // разные модели дают разные досье, и сравнивать прогоны стало бы не с чем.
                        model = model.trim().ifEmpty { properties.model.trim() },
                        maxFacts = maxFacts,
                        maxValueChars = maxValueChars,
                        maxTokens = maxTokens,
                    )
                },
            ),
        ),
    )

    /**
     * Агент один на приложение. Состояния в нём нет: и переписка, и её цена лежат
     * в [ChatStore], поэтому перезапуск не влияет ни на память, ни на замеры.
     */
    @Bean
    fun agent(
        llm: LlmClient,
        store: ChatStore,
        registry: StrategyRegistry,
        properties: AgentProperties,
        clock: Clock,
    ) = Agent(llm, store, registry, properties.toConfig(), clock)
}

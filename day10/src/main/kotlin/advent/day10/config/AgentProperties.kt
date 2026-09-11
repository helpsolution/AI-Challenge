package advent.day10.config

import advent.day10.agent.AgentConfig
import advent.day10.chat.StrategyId
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Настройки агента из `application.yml`.
 *
 * Отдельный класс от `AgentConfig` нужен затем, что у них разные обязанности: этот
 * умеет читаться из конфига и потому обязан иметь значения по умолчанию для всего,
 * а `AgentConfig` умеет себя проверять и потому не терпит бессмыслицы. Смешивать —
 * значит либо проверять настройки в Spring, либо не проверять вовсе.
 */
@ConfigurationProperties(prefix = "agent")
data class AgentProperties(
    val name: String = "Барсик",
    val persona: String = "",
    val model: String = "deepseek-chat",
    val temperature: Double = 0.7,
    val maxTokens: Int = 1024,
    /** Лимит контекста модели. Меняется вместе с `model`. */
    val contextLimit: Int = 1_000_000,
    val context: ContextDefaults = ContextDefaults(),
) {
    fun toConfig() = AgentConfig(
        name = name.trim(),
        persona = persona.trim(),
        model = model.trim(),
        temperature = temperature,
        maxTokens = maxTokens,
        contextLimit = contextLimit,
        defaultStrategy = context.strategy,
        defaultWindowSize = context.windowSize,
    )
}

/**
 * Значения по умолчанию для новой сессии. Именно по умолчанию: работающая сессия читает
 * свою стратегию и своё окно из базы, а не отсюда.
 */
data class ContextDefaults(
    val strategy: StrategyId = StrategyId.SLIDING_WINDOW,
    /**
     * Сколько последних сообщений уходит модели дословно.
     *
     * Четыре — это две пары «вопрос-ответ». Число намеренно маленькое: у `deepseek-chat`
     * контекст миллион токенов, и при просторном окне ни одна стратегия на сценарии
     * из пятнадцати реплик ничего не потеряет — сравнивать будет нечего.
     */
    val windowSize: Int = 4,
    val facts: FactsProperties = FactsProperties(),
)

/**
 * Досье стратегии фактов.
 *
 * [maxFacts] — то единственное, что мешает стратегии выродиться в «отправляем всё».
 * Без потолка досье растёт вместе с разговором, только медленнее, и на длинной дистанции
 * приходит туда же, откуда мы уходили.
 */
data class FactsProperties(
    /** Модель извлекателя. Пусто — та же, что отвечает пользователю. */
    val model: String = "",
    val maxFacts: Int = 25,
    val maxValueChars: Int = 200,
    val maxTokens: Int = 512,
)

package advent.day9.config

import advent.day9.agent.AgentSettings
import advent.day9.context.ContextMode
import advent.day9.context.ContextPolicy
import org.springframework.boot.context.properties.ConfigurationProperties

/** Настройки агента из `application.yml`. */
@ConfigurationProperties(prefix = "agent")
data class AgentProperties(
    val name: String = "Барсик",
    val persona: String = "",
    val model: String = "deepseek-chat",
    val temperature: Double = 0.8,
    val maxTokens: Int = 512,
    /** Лимит контекста модели. Меняется вместе с `model` и `llm.provider`. */
    val contextLimit: Int = 1_000_000,
    val context: ContextProperties = ContextProperties(),
) {
    /**
     * Управление контекстом — то, что этот день добавляет к дню 8.
     *
     * Все четыре значения задаются конфигом и меняются рестартом. Это не экономия
     * на интерфейсе: сравнивать надо два прогона целиком, а переключатель на странице
     * привёл бы к базе, где половина ходов прошла с конспектом, а половина без, — и вывод
     * о качестве ответов было бы не на чем строить.
     */
    data class ContextProperties(
        val mode: ContextMode = ContextMode.SUMMARY,
        val keepLast: Int = 6,
        val summarizeEvery: Int = 10,
        val summaryMaxTokens: Int = 600,
        /** Пусто — сворачиваем той же моделью, что отвечает пользователю. */
        val summaryModel: String = "",
    )

    fun toSettings() = AgentSettings(
        name = name.trim(),
        persona = persona.trim(),
        model = model.trim(),
        temperature = temperature,
        maxTokens = maxTokens,
        contextLimit = contextLimit,
        context = ContextPolicy(
            mode = context.mode,
            keepLast = context.keepLast,
            summarizeEvery = context.summarizeEvery,
            summaryMaxTokens = context.summaryMaxTokens,
            summaryModel = context.summaryModel.trim().takeIf { it.isNotBlank() },
        ),
    )
}

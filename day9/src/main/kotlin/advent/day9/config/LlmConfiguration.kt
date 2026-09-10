package advent.day9.config

import advent.day9.llm.DeepSeekClient
import advent.day9.llm.LlmClient
import advent.day9.llm.OpenRouterClient
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

/**
 * Собирает того клиента, который выбран в конфиге.
 *
 * Клиенты нарочно не помечены `@Service`: два бина одного контракта заставили бы агента
 * выбирать между ними, а выбор провайдера — решение конфигурации, а не кода. Живёт он
 * в одной строке `llm.provider`, и агент получает ровно один клиент, не зная, какой.
 */
@Configuration
class LlmConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun llmClient(
        properties: LlmProperties,
        deepSeek: DeepSeekProperties,
        openRouter: OpenRouterProperties,
        objectMapper: ObjectMapper,
    ): LlmClient = when (properties.provider) {
        LlmProvider.DEEPSEEK -> {
            log.info("Провайдер: DeepSeek, {}", deepSeek.baseUrl)
            DeepSeekClient(deepSeek, objectMapper)
        }

        LlmProvider.OPENROUTER -> {
            log.info(
                "Провайдер: OpenRouter, {}. Сжатие контекста: {}",
                openRouter.baseUrl,
                if (openRouter.contextCompression) "включено — историю урежут молча" else "выключено — переполнение упадёт с ошибкой",
            )
            OpenRouterClient(openRouter, objectMapper)
        }
    }
}

package advent.day9.config

import advent.day9.agent.Agent
import advent.day9.context.ContextMode
import advent.day9.context.Summarizer
import advent.day9.llm.LlmClient
import advent.day9.store.ChatStore
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AgentConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Агент один на приложение. Состояния в нём нет: и переписка, и конспект, и расход
     * лежат в [ChatStore], поэтому перезапуск не влияет ни на память, ни на график.
     */
    @Bean
    fun agent(llm: LlmClient, store: ChatStore, properties: AgentProperties): Agent {
        val settings = properties.toSettings()
        val policy = settings.context

        if (policy.mode == ContextMode.RAW) {
            log.info("Контекст: RAW — вся переписка уходит модели дословно, как в дне 8")
        } else {
            log.info(
                "Контекст: SUMMARY — дословно последние {} сообщений, остальное конспектом. " +
                    "Сворачивание пачками по {}, потолок конспекта {} токенов, модель конспекта: {}",
                policy.keepLast, policy.summarizeEvery, policy.summaryMaxTokens, settings.summaryModel,
            )
        }

        return Agent(
            llm = llm,
            store = store,
            settings = settings,
            summarizer = Summarizer(llm, policy, settings.summaryModel),
        )
    }
}

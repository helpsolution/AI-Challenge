package advent.day7.config

import advent.day7.agent.Agent
import advent.day7.llm.LlmClient
import advent.day7.store.MessageStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AgentConfiguration {

    /**
     * Агент один на приложение. Состояния в нём нет: всё, что он помнит, лежит в [MessageStore],
     * поэтому перезапуск приложения на память не влияет.
     */
    @Bean
    fun agent(llm: LlmClient, store: MessageStore, properties: AgentProperties): Agent =
        Agent(llm, store, properties.toSettings())
}

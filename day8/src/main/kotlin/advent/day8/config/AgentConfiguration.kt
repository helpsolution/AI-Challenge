package advent.day8.config

import advent.day8.agent.Agent
import advent.day8.llm.LlmClient
import advent.day8.store.ChatStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AgentConfiguration {

    /**
     * Агент один на приложение. Состояния в нём нет: и переписка, и её цена лежат
     * в [ChatStore], поэтому перезапуск приложения не влияет ни на память, ни на график
     * расхода.
     */
    @Bean
    fun agent(llm: LlmClient, store: ChatStore, properties: AgentProperties): Agent =
        Agent(llm, store, properties.toSettings())
}

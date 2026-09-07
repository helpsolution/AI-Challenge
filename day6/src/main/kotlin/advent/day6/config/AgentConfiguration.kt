package advent.day6.config

import advent.day6.agent.Agent
import advent.day6.llm.LlmClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AgentConfiguration {

    /**
     * Агент один на приложение и живёт столько же, сколько оно: имя, характер, счётчики
     * и журнал — в нём, а не в запросе. Именно это отличает его от вызова API,
     * который заканчивается вместе с ответом.
     */
    @Bean
    fun agent(llm: LlmClient, agentProperties: AgentProperties, deepSeek: DeepSeekProperties): Agent =
        Agent(agentProperties.toSettings(), llm, deepSeek.allowedModels)
}

package advent.day11.config

import advent.day11.agent.Agent
import advent.day11.agent.MemoryRouter
import advent.day11.agent.PromptBuilder
import advent.day11.llm.LlmClient
import advent.day11.llm.OpenRouterClient
import advent.day11.memory.LongTermMemory
import advent.day11.memory.ShortTermMemory
import advent.day11.memory.WorkingMemory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.time.Clock

@Configuration
class AgentConfiguration {
    @Bean
    fun llmClient(properties: OpenRouterProperties, objectMapper: ObjectMapper): LlmClient =
        OpenRouterClient(properties, objectMapper)

    @Bean
    fun promptBuilder(
        shortTerm: ShortTermMemory,
        working: WorkingMemory,
        longTerm: LongTermMemory,
        properties: AgentProperties,
    ): PromptBuilder = PromptBuilder(shortTerm, working, longTerm, properties)

    @Bean
    fun memoryRouter(
        working: WorkingMemory,
        longTerm: LongTermMemory,
        clock: Clock,
    ): MemoryRouter = MemoryRouter(working, longTerm, clock)

    @Bean
    fun agent(
        llm: LlmClient,
        shortTerm: ShortTermMemory,
        working: WorkingMemory,
        promptBuilder: PromptBuilder,
        memoryRouter: MemoryRouter,
        objectMapper: ObjectMapper,
        clock: Clock,
        properties: AgentProperties,
    ): Agent = Agent(llm, shortTerm, working, promptBuilder, memoryRouter, objectMapper, clock, properties)
}

package advent.day13.config

import advent.day13.agent.Agent
import advent.day13.agent.MemoryRouter
import advent.day13.agent.PromptBuilder
import advent.day13.inspection.AgentInspection
import advent.day13.llm.LlmClient
import advent.day13.llm.OpenRouterClient
import advent.day13.memory.LongTermMemory
import advent.day13.memory.ShortTermMemory
import advent.day13.memory.WorkingMemory
import advent.day13.profile.ProfileStore
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
        profiles: ProfileStore,
        properties: AgentProperties,
    ): PromptBuilder = PromptBuilder(shortTerm, working, longTerm, profiles, properties)

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
        profiles: ProfileStore,
        promptBuilder: PromptBuilder,
        memoryRouter: MemoryRouter,
        objectMapper: ObjectMapper,
        clock: Clock,
        properties: AgentProperties,
        inspection: AgentInspection,
    ): Agent = Agent(llm, shortTerm, working, profiles, promptBuilder, memoryRouter, objectMapper, clock, properties, inspection)
}

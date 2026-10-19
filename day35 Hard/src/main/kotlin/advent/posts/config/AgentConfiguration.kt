package advent.posts.config

import advent.posts.agent.Agent
import advent.posts.agent.MemoryRouter
import advent.posts.agent.PromptBuilder
import advent.posts.llm.LlmClient
import advent.posts.llm.DeepSeekClient
import advent.posts.memory.LongTermMemory
import advent.posts.memory.ShortTermMemory
import advent.posts.memory.WorkingMemory
import advent.posts.profile.ProfileStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.time.Clock

@Configuration
class AgentConfiguration {
    @Bean
    fun llmClient(properties: LlmProperties, objectMapper: ObjectMapper): LlmClient =
        DeepSeekClient(properties, objectMapper)

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
    ): Agent = Agent(llm, shortTerm, working, profiles, promptBuilder, memoryRouter, objectMapper, clock, properties)
}

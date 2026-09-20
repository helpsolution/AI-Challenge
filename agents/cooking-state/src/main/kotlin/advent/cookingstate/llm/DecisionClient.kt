package advent.cookingstate.llm

import advent.cookingstate.agent.AgentDecision
import advent.cookingstate.agent.CookingSession

fun interface DecisionClient {
    fun decide(session: CookingSession, message: String): AgentDecision
}

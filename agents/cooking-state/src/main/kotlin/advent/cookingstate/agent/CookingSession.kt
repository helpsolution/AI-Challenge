package advent.cookingstate.agent

import java.time.Instant

enum class CookingState { GATHERING, CHOOSING, READY, COOKING, DONE }

data class DishSuggestion(val name: String, val reason: String)

data class Recipe(val name: String, val steps: List<String>)

data class CookingContext(
    val ingredients: List<String> = emptyList(),
    val timeMinutes: Int? = null,
    val servings: Int? = null,
    val restrictions: List<String> = emptyList(),
    val suggestions: List<DishSuggestion> = emptyList(),
    val recipe: Recipe? = null,
    /** Номер текущего шага с нуля; в DONE равен числу шагов. */
    val currentStepIndex: Int? = null,
)

data class CookingSession(
    val id: String,
    val state: CookingState,
    val context: CookingContext,
    val reply: String,
    val version: Int,
    val updatedAt: Instant,
)

/** Полный снимок контекста после прочтения очередного сообщения. */
data class AgentDecision(
    val nextState: CookingState,
    val ingredients: List<String> = emptyList(),
    val timeMinutes: Int? = null,
    val servings: Int? = null,
    val restrictions: List<String> = emptyList(),
    val suggestions: List<DishSuggestion> = emptyList(),
    val reply: String,
    val recipe: Recipe? = null,
    val advanceStep: Boolean = false,
)

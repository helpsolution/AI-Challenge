package advent.cookingstate.agent

import advent.cookingstate.config.CookingProperties
import advent.cookingstate.llm.DecisionClient
import advent.cookingstate.llm.LlmException
import advent.cookingstate.store.SessionStore
import org.springframework.stereotype.Service
import java.time.Instant

class SessionNotFoundException(id: String) : RuntimeException("Сессия $id не найдена")
class SessionConflictException : RuntimeException("Сессия изменилась во время обработки. Повторите сообщение")

@Service
class CookingAgent(
    private val properties: CookingProperties,
    private val store: SessionStore,
    private val decisionClient: DecisionClient,
) {
    fun create(): CookingSession = store.create()

    fun get(id: String): CookingSession = store.find(id) ?: throw SessionNotFoundException(id)

    fun message(id: String, text: String): CookingSession {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "Сообщение не должно быть пустым" }
        require(trimmed.length <= properties.maxMessageChars) {
            "Сообщение должно быть не длиннее ${properties.maxMessageChars} символов"
        }
        val current = get(id)
        val decision = decisionClient.decide(current, trimmed)
        if (decision.reply.isBlank()) throw LlmException("Модель вернула пустой ответ")
        requireTransition(current.state, decision.nextState)
        val context = nextContext(current, decision)
        val reply = when {
            current.state == CookingState.READY && decision.nextState == CookingState.COOKING -> stepReply(context)
            current.state == CookingState.COOKING && decision.nextState == CookingState.COOKING && decision.advanceStep -> stepReply(context)
            current.state == CookingState.COOKING && decision.nextState == CookingState.DONE ->
                "Готово! ${context.recipe!!.name} приготовлено. Можно начать новую сессию или сказать «начать заново»."
            else -> decision.reply.trim()
        }
        val updated = current.copy(
            state = decision.nextState,
            context = context,
            reply = reply,
            version = current.version + 1,
            updatedAt = Instant.now(),
        )
        if (!store.save(current.version, updated)) throw SessionConflictException()
        return updated
    }

    private fun requireTransition(from: CookingState, to: CookingState) {
        val allowed = when (from) {
            CookingState.GATHERING -> setOf(CookingState.GATHERING, CookingState.CHOOSING)
            CookingState.CHOOSING -> setOf(CookingState.GATHERING, CookingState.CHOOSING, CookingState.READY)
            CookingState.READY -> setOf(CookingState.GATHERING, CookingState.CHOOSING, CookingState.READY, CookingState.COOKING)
            CookingState.COOKING -> setOf(CookingState.GATHERING, CookingState.CHOOSING, CookingState.COOKING, CookingState.DONE)
            CookingState.DONE -> setOf(CookingState.DONE, CookingState.GATHERING)
        }
        if (to !in allowed) throw LlmException("Недопустимый переход: $from → $to")
    }

    private fun nextContext(current: CookingSession, decision: AgentDecision): CookingContext {
        if (current.state == CookingState.DONE && decision.nextState == CookingState.GATHERING) return CookingContext()

        val previous = current.context
        if (current.state == CookingState.READY && decision.nextState == CookingState.READY) return previous
        if (current.state == CookingState.READY && decision.nextState == CookingState.COOKING) {
            if (previous.recipe == null || decision.advanceStep) throw LlmException("Готовка ещё не готова к началу")
            return previous.copy(currentStepIndex = 0)
        }
        if (current.state == CookingState.COOKING && decision.nextState in setOf(CookingState.COOKING, CookingState.DONE)) {
            val recipe = previous.recipe ?: throw LlmException("Рецепт не выбран")
            val step = previous.currentStepIndex ?: throw LlmException("Неизвестен текущий шаг")
            if (decision.nextState == CookingState.COOKING) {
                if (!decision.advanceStep) return previous
                if (step >= recipe.steps.lastIndex) throw LlmException("После последнего шага нужно завершить готовку")
                return previous.copy(currentStepIndex = step + 1)
            }
            if (!decision.advanceStep || step != recipe.steps.lastIndex) {
                throw LlmException("Нельзя завершить готовку до последнего шага")
            }
            return previous.copy(currentStepIndex = recipe.steps.size)
        }
        if (current.state == CookingState.DONE && decision.nextState == CookingState.DONE) return previous

        val ingredients = decision.ingredients.clean(30)
        val restrictions = decision.restrictions.clean(20)
        val time = decision.timeMinutes?.takeIf { it in 1..1440 }
        val servings = decision.servings?.takeIf { it in 1..100 }
        if ((decision.timeMinutes != null && time == null) || (decision.servings != null && servings == null)) {
            throw LlmException("Модель вернула некорректное время или число порций")
        }
        val basics = CookingContext(ingredients, time, servings, restrictions)
        if (decision.nextState == CookingState.GATHERING) return basics
        if (ingredients.isEmpty() || time == null) throw LlmException("Модель предложила переход без продуктов или времени")

        if (decision.nextState == CookingState.CHOOSING) {
            val suggestions = decision.suggestions.take(3).map { DishSuggestion(it.name.trim(), it.reason.trim()) }
            if (suggestions.size < 2 || suggestions.any { it.name.isBlank() || it.reason.isBlank() } ||
                suggestions.map { it.name.lowercase() }.distinct().size != suggestions.size
            ) throw LlmException("Модель не предложила два разных блюда")
            return basics.copy(suggestions = suggestions)
        }

        if (current.state == CookingState.CHOOSING && decision.nextState == CookingState.READY) {
            val recipe = decision.recipe?.let { Recipe(it.name.trim(), it.steps.map(String::trim)) }
                ?: throw LlmException("Модель не составила рецепт выбранного блюда")
            if (recipe.name.isBlank() || recipe.steps.size !in 2..12 ||
                recipe.steps.any { it.isBlank() || it.length > 400 } ||
                current.context.suggestions.none { it.name.equals(recipe.name, ignoreCase = true) }
            ) throw LlmException("Модель вернула некорректный рецепт или блюдо не из списка")
            return basics.copy(suggestions = current.context.suggestions, recipe = recipe)
        }

        throw LlmException("Не удалось применить переход состояния")
    }

    private fun stepReply(context: CookingContext): String {
        val recipe = context.recipe!!
        val step = context.currentStepIndex!!
        return "Шаг ${step + 1} из ${recipe.steps.size}: ${recipe.steps[step]} Когда закончишь, напиши «готово»."
    }

    private fun List<String>.clean(maxCount: Int): List<String> {
        if (size > maxCount || any { it.length > 100 }) throw LlmException("Модель вернула слишком длинный список")
        return map(String::trim).filter(String::isNotBlank).distinct()
    }
}

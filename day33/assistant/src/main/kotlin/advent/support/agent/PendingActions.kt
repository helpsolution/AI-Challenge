package advent.support.agent

import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PendingAction(val id: String, val customerId: String, val tool: String, val arguments: Map<String, Any?>)

// Предложенные моделью записи в CRM. Подтвердить можно только то, что модель действительно предложила,
// и только один раз: повторное нажатие не создаст второе обращение.
@Component
class PendingActions {
    private val actions = ConcurrentHashMap<String, PendingAction>()

    fun add(customerId: String, tool: String, arguments: Map<String, Any?>): PendingAction =
        PendingAction(UUID.randomUUID().toString(), customerId, tool, arguments).also { actions[it.id] = it }

    fun take(id: String, customerId: String): PendingAction? =
        actions[id]?.takeIf { it.customerId == customerId }?.let { actions.remove(id) }
}

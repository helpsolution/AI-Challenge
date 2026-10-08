package advent.lab.task

import advent.lab.ollama.ChatMessage
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue

/** Разметка обращения: пять полей, которые модель должна извлечь. Порядок полей — порядок генерации в схеме. */
data class Fields(
    val category: String?,
    val action: String?,
    val urgency: String?,
    @JsonProperty("order_id") val orderId: String?,
    val amount: Double?,
) {
    operator fun get(name: String): Any? = when (name) {
        "category" -> category
        "action" -> action
        "urgency" -> urgency
        "order_id" -> orderId
        "amount" -> amount
        else -> throw IllegalArgumentException("Нет поля $name")
    }
}

/** Обращение из набора. set: tune — на нём подбираются настройки, check — контроль, на нём их не подбирали. */
data class Ticket(val id: String, val set: String, val text: String, val expected: Fields)

data class Shot(val text: String, val answer: Fields)

val FIELD_NAMES = listOf("category", "action", "urgency", "order_id", "amount")

val CATEGORIES = listOf("delivery", "return", "defect", "payment", "account", "product")
val ACTIONS = listOf("track", "cancel", "refund", "exchange", "fix", "info")
val URGENCIES = listOf("low", "medium", "high")

// Задача дня: обращение покупателя → JSON из пяти полей. Набор, промпты и примеры лежат в resources/task.
@Component
class TicketTask(private val json: JsonMapper) {

    val tickets: List<Ticket> = json.readValue(resource("tickets.json"))
    val shots: List<Shot> = json.readValue(resource("shots.json"))
    val naivePrompt: String = resource("prompt-naive.txt").trim()
    val optimizedPrompt: String = resource("prompt-optimized.txt").trim()

    // Перечисления в схеме: модель физически не может написать категорию, которой нет в списке.
    val schema: JsonNode = json.valueToTree(
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "category" to mapOf("type" to "string", "enum" to CATEGORIES),
                "action" to mapOf("type" to "string", "enum" to ACTIONS),
                "urgency" to mapOf("type" to "string", "enum" to URGENCIES),
                "order_id" to mapOf("type" to listOf("string", "null")),
                "amount" to mapOf("type" to listOf("number", "null")),
            ),
            "required" to FIELD_NAMES,
        )
    )

    fun set(name: String): List<Ticket> = when (name) {
        "all" -> tickets
        else -> tickets.filter { it.set == name }.ifEmpty { throw IllegalArgumentException("Нет набора «$name»: tune, check или all") }
    }

    fun ticket(id: String): Ticket? = tickets.firstOrNull { it.id == id }

    /** Системный промпт, затем примеры парами «обращение → ответ», затем само обращение. */
    fun messages(system: String, fewShot: Boolean, text: String): List<ChatMessage> = buildList {
        if (system.isNotBlank()) add(ChatMessage("system", system))
        if (fewShot) addAll(shotMessages())
        add(ChatMessage("user", text))
    }

    fun shotMessages(): List<ChatMessage> = shots.flatMap { listOf(ChatMessage("user", it.text), ChatMessage("assistant", answer(it.answer))) }

    private fun answer(fields: Fields): String = json.writeValueAsString(
        linkedMapOf(
            "category" to fields.category, "action" to fields.action, "urgency" to fields.urgency,
            "order_id" to fields.orderId, "amount" to fields.amount?.let { if (it % 1.0 == 0.0) it.toLong() else it },
        )
    )

    private fun resource(name: String): String = ClassPathResource("task/$name").getContentAsString(Charsets.UTF_8)
}

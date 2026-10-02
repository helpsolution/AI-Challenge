package advent.rag.memory

import advent.rag.agent.PromptBuilder
import advent.rag.llm.ChatMessage
import advent.rag.llm.Completion
import advent.rag.llm.LlmClient
import com.fasterxml.jackson.annotation.JsonIgnore
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

@ConfigurationProperties("task-memory")
data class TaskMemoryProperties(val enabled: Boolean)

/** Что пользователь зафиксировал в диалоге: держится дольше, чем окно истории в промпте. */
data class TaskState(
    val goal: String? = null,
    val clarified: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val terms: List<String> = emptyList(),
) {
    @get:JsonIgnore
    val isEmpty: Boolean get() = goal == null && clarified.isEmpty() && constraints.isEmpty() && terms.isEmpty()
}

/** field: goal, clarified, constraints, terms; kind: added, removed, changed. previous — только у changed. */
data class MemoryChange(val field: String, val kind: String, val text: String, val previous: String? = null)

/** status: updated, unchanged, fallback (ответ модели отклонён, память прежняя), disabled. */
data class MemoryUpdate(
    val enabled: Boolean,
    val status: String,
    val reason: String?,
    val changes: List<MemoryChange>,
    val durationMs: Long,
)

data class MemoryResult(val previous: TaskState, val state: TaskState, val update: MemoryUpdate, val completion: Completion? = null)

@Component
class TaskMemory(private val llm: LlmClient, private val prompts: PromptBuilder, private val json: JsonMapper,
                 private val properties: TaskMemoryProperties) {
    val enabled: Boolean get() = properties.enabled

    fun update(state: TaskState, history: List<ChatMessage>, message: String): MemoryResult {
        if (!properties.enabled) return MemoryResult(state, state, MemoryUpdate(false, "disabled", null, emptyList(), 0))
        val completion = llm.complete(prompts.memory(json.writeValueAsString(state), history, message), jsonResponse = true, maxTokens = 1200, temperature = 0.0)
        val duration = completion.exchange.durationMs
        val parsed = runCatching {
            require(completion.finishReason != "length") { "модель не закончила JSON" }
            parse(json.readTree(completion.text))
        }
        val next = parsed.getOrElse {
            return MemoryResult(state, state, MemoryUpdate(true, "fallback", "Ответ модели отклонён: ${it.message}; память не изменена".take(300),
                emptyList(), duration), completion)
        }
        val changes = diff(state, next)
        return MemoryResult(state, next, MemoryUpdate(true, if (changes.isEmpty()) "unchanged" else "updated", null, changes, duration), completion)
    }

    private fun parse(node: JsonNode): TaskState {
        val goal = node.path("goal")
        require(goal.isNull || goal.isString) { "goal должен быть строкой или null" }
        return TaskState(goal.takeIf { it.isString }?.stringValue()?.trim()?.ifEmpty { null },
            list(node, "clarified"), list(node, "constraints"), list(node, "terms"))
    }

    private fun list(node: JsonNode, field: String): List<String> {
        val items = node.path(field)
        require(items.isArray && items.values().all { it.isString }) { "$field должен быть массивом строк" }
        return items.values().map { it.stringValue().trim() }.filter { it.isNotEmpty() }.distinct()
    }

    companion object {
        fun diff(before: TaskState, after: TaskState): List<MemoryChange> = buildList {
            when {
                before.goal == after.goal -> {}
                before.goal == null -> add(MemoryChange("goal", "added", after.goal!!))
                after.goal == null -> add(MemoryChange("goal", "removed", before.goal))
                else -> add(MemoryChange("goal", "changed", after.goal, before.goal))
            }
            listOf("clarified" to TaskState::clarified, "constraints" to TaskState::constraints, "terms" to TaskState::terms)
                .forEach { (field, items) ->
                    (items(before) - items(after).toSet()).forEach { add(MemoryChange(field, "removed", it)) }
                    (items(after) - items(before).toSet()).forEach { add(MemoryChange(field, "added", it)) }
                }
        }
    }
}

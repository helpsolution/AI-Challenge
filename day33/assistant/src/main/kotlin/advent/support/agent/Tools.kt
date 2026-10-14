package advent.support.agent

import advent.support.crm.CrmSession
import advent.support.crm.CrmToolInfo
import advent.support.embedding.EmbeddingException
import advent.support.knowledge.DocsSearch
import advent.support.llm.FunctionSpec
import advent.support.llm.ToolSpec
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

data class ToolOutcome(val content: String, val ok: Boolean, val detail: Any?)

// Инструмент агента: name и description читает модель, parameters — JSON Schema аргументов, execute — логика.
interface AgentTool {
    val name: String
    val description: String
    val parameters: JsonNode
    // true — запись: модель только предлагает вызов, выполняет его клиент кнопкой подтверждения.
    val needsConfirmation: Boolean
    fun execute(arguments: ObjectNode): ToolOutcome
}

// Инструмент CRM из MCP. customer_id убран из схемы, которую видит модель, и всегда подставляется сервером:
// ни вопрос клиента, ни текст обращения не заставят модель прочитать чужие данные.
class CrmTool(private val info: CrmToolInfo, private val session: CrmSession, private val customerId: String,
    override val needsConfirmation: Boolean, private val json: JsonMapper) : AgentTool {
    override val name = info.name
    // Модель осторожничает с записью и спрашивает словами, вместо того чтобы вызвать инструмент. Объясняем, что вызов безопасен.
    override val description = if (!needsConfirmation) info.description else info.description +
        " Вызов ничего не записывает сразу: клиент увидит кнопку подтверждения и решит сам. Поэтому вызывай без предварительного вопроса."
    override val parameters: JsonNode = (info.inputSchema.deepCopy() as ObjectNode).apply {
        (get("properties") as? ObjectNode)?.remove(CUSTOMER_ID)
        get("required")?.let { required ->
            set("required", json.valueToTree<JsonNode>(required.values().map { it.asString() }.filter { it != CUSTOMER_ID }))
        }
    }

    override fun execute(arguments: ObjectNode): ToolOutcome {
        val call = session.call(name, bound(arguments))
        return if (call.ok) ToolOutcome(call.data.toString(), true, call)
        else ToolOutcome("Ошибка инструмента: ${call.error}", false, call)
    }

    fun bound(arguments: ObjectNode): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val args = json.convertValue(arguments, Map::class.java) as Map<String, Any?>
        return args - CUSTOMER_ID + (CUSTOMER_ID to customerId)
    }

    companion object { const val CUSTOMER_ID = "customer_id" }
}

// Номера фрагментов сквозные на весь ответ: повторно найденный фрагмент сохраняет свой номер.
class SourceBook {
    private val sources = LinkedHashMap<String, DocSource>()
    val all: List<DocSource> get() = sources.values.toList()
    fun add(chunkId: String, create: (Int) -> DocSource): DocSource = sources.getOrPut(chunkId) { create(sources.size + 1) }
}

class SearchDocsTool(private val search: DocsSearch, private val sources: SourceBook, json: JsonMapper) : AgentTool {
    override val name = "search_docs"
    override val description = "Поиск по документации и FAQ «Ладьи»: вход и блокировки, 2FA, SSO, тарифы и оплата, " +
        "режим «Только чтение», известные проблемы приложений, импорт и экспорт, мобильные приложения, работа поддержки. " +
        "Возвращает пронумерованные фрагменты [n] — ссылайся на них в ответе."
    override val needsConfirmation = false
    override val parameters: JsonNode = json.readTree("""{"type":"object","properties":{"query":{"type":"string",
        "description":"Запрос по-русски: суть проблемы или вопроса, например «блокировка после неудачных попыток входа»"}},
        "required":["query"]}""")

    override fun execute(arguments: ObjectNode): ToolOutcome {
        val query = arguments.path("query").asString("").trim()
        if (query.isEmpty()) return ToolOutcome("Ошибка: нужен непустой query", false, null)
        val found = try {
            search.search(query)
        } catch (e: EmbeddingException) {
            return ToolOutcome("Поиск по документации недоступен: ${e.message}. Ответь по данным CRM и честно скажи, " +
                "что документацию сейчас проверить нельзя.", false, mapOf("query" to query, "error" to e.message))
        }
        val numbered = found.map { scored ->
            val chunk = scored.chunk
            sources.add(chunk.chunkId) { DocSource(it, chunk.source, chunk.title, chunk.section, chunk.content, scored.score) }
        }
        val text = numbered.joinToString("\n\n") { "[${it.number}] ${it.title} › ${it.section ?: "Введение"}\n${it.text}" }
        return ToolOutcome(text, true, mapOf("query" to query,
            "fragments" to numbered.map { mapOf("number" to it.number, "file" to it.file, "section" to it.section, "score" to it.score) }))
    }
}

class ToolBox(val tools: List<AgentTool>) {
    val specs: List<ToolSpec> = tools.map { ToolSpec(FunctionSpec(it.name, it.description, it.parameters)) }
    fun find(name: String): AgentTool? = tools.firstOrNull { it.name == name }
}

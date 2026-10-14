package advent.support.agent

import advent.support.crm.CrmClient
import advent.support.crm.CrmProperties
import advent.support.crm.CrmSession
import advent.support.knowledge.DocsSearch
import advent.support.llm.*
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

typealias Emit = (event: String, data: Any) -> Unit

// Пайплайн одного ответа:
// вход (профиль клиента и обращение через MCP) → цикл «модель выбирает инструменты → сервер их выполняет» → ответ.
@Component
class SupportAgent(private val crm: CrmClient, private val crmProperties: CrmProperties, private val docs: DocsSearch,
    private val llm: DeepSeekClient, private val pending: PendingActions, private val properties: AgentProperties,
    private val json: JsonMapper) {

    fun validate(request: SupportRequest) {
        require(CUSTOMER.matches(request.customerId)) { "Выберите клиента" }
        require(request.message.isNotBlank() && request.message.length <= 4000) { "Сообщение: от 1 до 4000 символов" }
        require(request.history.all { it.role in setOf("user", "assistant") }) { "Некорректная история" }
    }

    fun answer(request: SupportRequest, emit: Emit): SupportAnswer {
        validate(request)
        val run = Run(request, emit)
        return try {
            crm.session { session -> run.execute(session) }
        } catch (e: Exception) {
            run.step("note", "Ошибка пайплайна", "error", 0, e.message ?: e.javaClass.simpleName)
            run.finish("failed", "Не получилось обработать вопрос: ${e.message ?: "ошибка сервиса"}")
        }
    }

    private inner class Run(val request: SupportRequest, val emit: Emit) {
        val started = System.nanoTime()
        val sources = SourceBook()
        val warnings = mutableListOf<String>()
        var action: ProposedAction? = null
        var llmCalls = 0
        var toolCalls = 0
        var promptTokens = 0
        var completionTokens = 0
        var model = llm.model

        fun step(kind: String, title: String, status: String, durationMs: Long, detail: Any?) =
            emit("step", Step(kind, title, status, durationMs, detail))

        fun execute(session: CrmSession): SupportAnswer {
            // Вход пайплайна: кто спрашивает и о каком обращении — без участия модели.
            val profile = session.call("get_customer", mapOf(CrmTool.CUSTOMER_ID to request.customerId))
            step("crm", "CRM · get_customer", if (profile.ok) "ok" else "error", profile.durationMs, profile)
            if (!profile.ok) return finish("failed", "Не удалось получить данные клиента из CRM: ${profile.error}")
            val ticket = request.ticketId?.let { id ->
                session.call("get_ticket", mapOf(CrmTool.CUSTOMER_ID to request.customerId, "ticket_id" to id)).also {
                    step("crm", "CRM · get_ticket #$id", if (it.ok) "ok" else "error", it.durationMs, it)
                    if (!it.ok) warnings += "Обращение #$id не загружено: ${it.error}"
                }
            }

            val toolbox = toolbox(session)
            val messages = mutableListOf<Message>(SystemMessage(Prompts.system(profile.data!!, ticket?.data)))
            request.history.takeLast(properties.historyTurns * 2).forEach {
                // Номера [n] прошлых ответов указывали на другие фрагменты — в истории они только запутают модель.
                messages += if (it.role == "user") UserMessage(it.content) else AssistantMessage(it.content.replace(CITATION, ""))
            }
            messages += UserMessage(request.message.trim())

            for (number in 1..properties.maxSteps) {
                // На последнем шаге инструменты запрещены: модель должна ответить тем, что уже собрала.
                val last = number == properties.maxSteps
                val turn = llm.stream(messages, toolbox.specs, if (last) "none" else "auto",
                    onDelta = { emit("delta", mapOf("text" to it)) },
                    onRetry = { reason ->
                        emit("discard", mapOf("text" to ""))
                        step("note", "Повтор запроса к DeepSeek", "error", 0, reason)
                    })
                llmCalls++; promptTokens += turn.promptTokens; completionTokens += turn.completionTokens; model = turn.model
                step("llm", "DeepSeek · шаг $number", "ok", turn.durationMs, mapOf(
                    "decision" to if (turn.toolCalls.isEmpty()) "ответ клиенту" else "вызвать: " + turn.toolCalls.joinToString(", ") { it.function.name },
                    "promptTokens" to turn.promptTokens, "completionTokens" to turn.completionTokens,
                    "attempts" to turn.attempts, "finishReason" to turn.finishReason))
                if (turn.toolCalls.isEmpty() || last) {
                    if (turn.finishReason == "length") warnings += "Ответ обрезан лимитом токенов"
                    return finish("answered", turn.content.trim())
                }
                if (turn.content.isNotBlank()) {
                    emit("discard", mapOf("text" to turn.content))
                    step("note", "Модель перед вызовом", "ok", 0, turn.content.trim())
                }
                messages += AssistantMessage(turn.content, turn.toolCalls)
                turn.toolCalls.forEach { call -> messages += ToolMessage(call.id, run(toolbox, call)) }
            }
            error("Цикл агента завершился без ответа")
        }

        private fun toolbox(session: CrmSession): ToolBox {
            val start = System.nanoTime()
            val available = session.tools()
            val allowed = crmProperties.autoTools + crmProperties.confirmTools
            val crmTools = available.filter { it.name in allowed }.map {
                CrmTool(it, session, request.customerId, it.name in crmProperties.confirmTools, json)
            }
            val missing = allowed - available.map { it.name }.toSet()
            if (missing.isNotEmpty()) warnings += "MCP не объявил инструменты: ${missing.joinToString()}"
            val toolbox = ToolBox(crmTools + SearchDocsTool(docs, sources, json))
            step("crm", "MCP · tools/list", if (missing.isEmpty()) "ok" else "error", (System.nanoTime() - start) / 1_000_000, mapOf(
                "auto" to toolbox.tools.filter { !it.needsConfirmation }.map { it.name },
                "confirm" to toolbox.tools.filter { it.needsConfirmation }.map { it.name },
                "hidden" to available.map { it.name }.filter { it !in allowed },
                "schemas" to toolbox.specs))
            return toolbox
        }

        private fun run(toolbox: ToolBox, call: ToolCall): String {
            toolCalls++
            val start = System.nanoTime()
            val name = call.function.name
            val arguments = runCatching { json.readTree(call.function.arguments) as? ObjectNode }.getOrNull()
            val tool = toolbox.find(name)
            val outcome = when {
                tool == null -> ToolOutcome("Ошибка: инструмента $name нет. Доступны: ${toolbox.tools.joinToString { it.name }}", false, null)
                arguments == null -> ToolOutcome("Ошибка: аргументы должны быть JSON-объектом, получено ${call.function.arguments.take(200)}", false, null)
                tool.needsConfirmation -> propose(tool as CrmTool, arguments)
                else -> tool.execute(arguments)
            }
            val kind = if (name == "search_docs") "docs" else "crm"
            step(kind, name, if (outcome.ok) "ok" else "error", (System.nanoTime() - start) / 1_000_000,
                mapOf("arguments" to (arguments ?: call.function.arguments), "result" to outcome.detail, "toModel" to outcome.content))
            if (!outcome.ok && name == "search_docs") warnings += outcome.content.substringBefore(". Ответь")
            return outcome.content
        }

        private fun propose(tool: CrmTool, arguments: ObjectNode): ToolOutcome {
            val bound = tool.bound(arguments)
            val created = pending.add(request.customerId, tool.name, bound)
            action = ProposedAction(created.id, tool.name, "Создать обращение в поддержку", bound - CrmTool.CUSTOMER_ID)
            return ToolOutcome("""{"status":"awaiting_confirmation","note":"Обращение ещё не создано: клиент видит кнопку «Создать обращение» и решит сам. Напиши ответ клиенту полностью — что выяснил и что делать — и одной фразой скажи про кнопку."}""",
                true, mapOf("pending" to created.id, "arguments" to bound))
        }

        fun finish(status: String, text: String): SupportAnswer {
            val all = sources.all
            val referenced = CITATION.findAll(text).flatMap { it.groupValues[1].split(SEPARATOR) }.map(String::toInt).distinct().toList()
            referenced.filter { number -> all.none { it.number == number } }.forEach { warnings += "Ссылка [$it] не соответствует найденным фрагментам" }
            val answer = SupportAnswer(status, text, request.customerId, request.ticketId, all,
                referenced.filter { number -> all.any { it.number == number } }.sorted(), action,
                llmCalls, toolCalls, promptTokens, completionTokens, llm.cost(promptTokens, completionTokens), model,
                (System.nanoTime() - started) / 1_000_000, warnings.distinct())
            emit("answer", answer)
            return answer
        }
    }

    private companion object {
        val CUSTOMER = Regex("c-\\d+")
        val CITATION = Regex("\\[(\\d+(?:\\s*[,;]\\s*\\d+)*)]")
        val SEPARATOR = Regex("\\s*[,;]\\s*")
    }
}

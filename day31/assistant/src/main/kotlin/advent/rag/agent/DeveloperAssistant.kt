package advent.rag.agent

import advent.rag.chunking.Chunk
import advent.rag.knowledge.Indexer
import advent.rag.project.*
import advent.rag.reranking.RerankedChunk
import advent.rag.trace.*
import org.springframework.stereotype.Component
import java.nio.file.Files

@Component
class DeveloperAssistant(private val documents: RepoDocuments, private val indexer: Indexer,
    private val retriever: ProjectRetriever, private val mcp: ProjectMcpClient, private val prompts: PromptBuilder,
    private val generator: VerifiedGenerator, private val journal: TraceJournal) {

    fun validate(request: HelpRequest) {
        require(request.message.isNotBlank() && request.message.length <= 6000) { "Сообщение: от 1 до 6000 символов" }
        require(request.scope == "repo" || Regex("day[1-9]\\d*").matches(request.scope)) { "Выберите repo или dayN" }
        require(request.history.size <= 12 && request.history.sumOf { it.content.length } <= 24000 &&
            request.history.all { it.role in setOf("user", "assistant") && it.content.length <= 6000 }) { "Некорректная история" }
        require(!request.message.trim().startsWith('/') || request.message.trim() == "/help" ||
            request.message.trim().startsWith("/help ")) { "Поддерживается команда /help" }
    }

    fun ask(request: HelpRequest, listener: (Step) -> Unit = {}): HelpAnswer {
        validate(request)
        val trace = Recorder(request.message, listener)
        var tools = emptyList<ToolProof>()
        var scope = request.scope
        var branch: String? = null
        var sources = emptyList<Source>()
        fun finish(status: AnswerStatus, answer: String, reason: String, quotes: List<advent.rag.grounding.Quote> = emptyList(),
            completion: advent.rag.llm.Completion? = null): HelpAnswer {
            val result = HelpAnswer(trace.id, scope, request.message, status, answer, reason, branch, sources, quotes, tools,
                trace.durationMs, completion?.model, completion?.promptTokens, completion?.completionTokens,
                tools.mapNotNull { it.error })
            journal.save(trace.snapshot(result))
            return result
        }
        try {
            val plan = RequestPlanner.plan(request, documents.scopes())
            scope = plan.scope
            trace.add("План запроса", "ok", 0, plan)
            if (plan.clarification != null) return finish(AnswerStatus.CLARIFY, plan.clarification, "Нужно уточнить проект")
            tools = mcp.fetch(plan.tools, scope, trace)
            branch = tools.firstOrNull { it.name == "git_branch" }?.data?.get("branch") as? String
            if (plan.question.isBlank()) return finish(AnswerStatus.HELP, GUIDE, "Справка по команде /help")
            if (plan.contextOnly) {
                val successful = tools.filter { it.error == null }
                sources = successful.mapIndexed { i, proof ->
                    Source(i + 1, "mcp", "mcp://${proof.name}", null, toolText(proof), null, null, null,
                        proof.data?.get("capturedAt") as? String)
                }
                if (successful.isEmpty()) return finish(AnswerStatus.FAILED,
                    "Не удалось получить текущее состояние проекта через MCP.", tools.mapNotNull { it.error }.joinToString("; "))
                val selected = successful.filter { it.name != "git_branch" || plan.tools.size == 1 ||
                    Regex("ветк|branch|commit|коммит", RegexOption.IGNORE_CASE).containsMatchIn(plan.question) }
                if (selected.isEmpty()) return finish(AnswerStatus.FAILED,
                    "Не удалось получить запрошенные данные через MCP.", tools.mapNotNull { it.error }.joinToString("; "))
                val answer = selected.joinToString("\n\n") { proof ->
                    val n = sources.first { it.source == "mcp://${proof.name}" }.number
                    toolText(proof) + " [$n]"
                }
                trace.add("Ответ по MCP", "ok", 0, "Данные инструмента показаны напрямую; LLM и RAG не нужны")
                return finish(AnswerStatus.ANSWERED, answer, "Состояние получено через MCP в этом запросе")
            }
            val start = System.nanoTime()
            val rebuilt = indexer.ensureFresh()
            trace.add("Актуальность индекса", "ok", (System.nanoTime() - start) / 1_000_000,
                rebuilt ?: "Содержимое и настройки индекса не изменились")
            val docs = retriever.search(plan.query, scope, trace)
            if (docs.isEmpty()) return finish(AnswerStatus.WEAK_CONTEXT,
                "В документации $scope не нашёл подтверждённого ответа. Уточните вопрос или выберите другой день.",
                "Ни один фрагмент не прошёл порог BGE ≥ 0; модель не вызывалась")
            val context = docs + tools.filter { it.error == null }.mapIndexed { i, proof ->
                val text = toolText(proof)
                RerankedChunk(Chunk("mcp:${proof.name}", "mcp://${proof.name}", proof.name, null,
                    "Текущее состояние проекта", index = i, start = 0, end = text.length, text = text), 1.0, 1.0)
            }
            sources = context.mapIndexed { i, found ->
                val chunk = found.chunk
                if (chunk.source.startsWith("mcp://")) {
                    val proof = tools.first { "mcp://${it.name}" == chunk.source }
                    Source(i + 1, "mcp", chunk.source, chunk.section, chunk.content, null, null, null,
                        proof.data?.get("capturedAt") as? String)
                } else {
                    val raw = Files.readString(documents.root.resolve(chunk.source))
                    val body = advent.rag.chunking.Document.parse(chunk.source, raw).text
                    val offset = raw.indexOf(body).coerceAtLeast(0)
                    Source(i + 1, "document", chunk.source, chunk.section, chunk.content, found.rerankScore,
                        raw.take(offset + chunk.start).count { it == '\n' } + 1,
                        raw.take(offset + chunk.end).count { it == '\n' } + 1)
                }
            }
            val prompt = prompts.answer(plan, request.history, context)
            trace.add("Контекст модели", "ok", 0, mapOf("scope" to scope, "sources" to sources, "messages" to prompt))
            val (completion, checked) = generator.generate(prompt, context, trace)
            return when (checked.status) {
                AnswerStatus.ANSWERED -> finish(checked.status, checked.answer!!,
                    "Все ссылки подкреплены цитатами из текущих источников", checked.quotes, completion)
                AnswerStatus.NO_ANSWER -> finish(checked.status,
                    "В найденной документации нет ответа на этот вопрос.", "Модель вернула unknown", completion = completion)
                else -> finish(AnswerStatus.UNVERIFIED,
                    "Ответ модели не прошёл проверку источников. Попробуйте уточнить вопрос.",
                    checked.errors.joinToString("; "), completion = completion)
            }
        } catch (e: Exception) {
            trace.add("Ошибка пайплайна", "error", 0, e.message ?: e.javaClass.simpleName)
            return finish(AnswerStatus.FAILED, "Не удалось завершить запрос. Подробности доступны в трассировке.",
                e.message ?: "Ошибка пайплайна")
        }
    }

    companion object {
        private val GUIDE = """
            /help <вопрос> — спросить о выбранном проекте.
            Можно указать день прямо в вопросе: /help Как запустить day30?
            /help На какой ветке я сейчас? — текущая git-ветка через MCP.
            /help Какие файлы есть в day31? — список файлов через MCP.
            /help Что изменено в рабочем дереве? — git status через MCP.
            Ответы о проекте используют README и docs со ссылками на источники.
        """.trimIndent()

        fun toolText(proof: ToolProof): String {
            val data = proof.data ?: return proof.error ?: "Нет данных"
            val text = when (proof.name) {
                "git_branch" -> "Текущая git-ветка: ${data["branch"] ?: "detached HEAD"}.\nCommit: ${data["commit"]}."
                "git_status" -> {
                    @Suppress("UNCHECKED_CAST") val changes = data["changes"] as? List<Map<String, Any?>> ?: emptyList()
                    "Рабочее дерево: ${if (data["clean"] == true) "нет изменений публичных файлов" else "изменений публичных файлов: ${data["count"]}"}." +
                        changes.joinToString("\n", prefix = if (changes.isEmpty()) "" else "\n") { "${it["status"]} ${it["path"]}" } +
                        if (data["truncated"] == true) "\nСписок изменений сокращён." else ""
                }
                "list_files" -> {
                    val files = data["files"] as? List<*> ?: emptyList<Any>()
                    "Файлы проекта ${data["scope"]}: всего ${data["count"]}.\n" + files.joinToString("\n") +
                        if (data["truncated"] == true) "\nПоказана только часть файлов; отсутствие пути здесь не доказывает его отсутствие в проекте." else ""
                }
                else -> data.toString()
            }
            return text + "\nПолучено через MCP: ${data["capturedAt"]}."
        }
    }
}

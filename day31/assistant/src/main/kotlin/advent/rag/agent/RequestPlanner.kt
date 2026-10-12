package advent.rag.agent

data class Plan(val question: String, val scope: String, val query: String, val tools: List<String>,
    val contextOnly: Boolean, val clarification: String? = null)

object RequestPlanner {
    fun plan(request: HelpRequest, scopes: List<String>): Plan {
        val question = request.message.trim().removePrefix("/help").trim()
        val days = DAY.findAll(question).map { "day${it.groupValues[1]}" }.distinct().toList()
        val scope = days.singleOrNull() ?: request.scope
        if (days.size > 1) return Plan(question, scope, question, listOf("git_branch"), false,
            "Выберите один день для вопроса: ${days.joinToString(", ")}. Так документация разных приложений не смешается.")
        if (scope != "repo" && scope !in scopes) return Plan(question, scope, question, listOf("git_branch"), false,
            "Для $scope нет README или docs. Выберите день, для которого есть документация.")
        val branch = BRANCH.containsMatchIn(question)
        val status = STATUS.containsMatchIn(question)
        val files = FILES.containsMatchIn(question)
        val docs = DOCS.containsMatchIn(question)
        val contextOnly = (branch || status || files) && !docs && !STRUCTURE.containsMatchIn(question)
        val tools = buildList {
            add("git_branch")
            if (status) add("git_status")
            if (files || STRUCTURE.containsMatchIn(question)) add("list_files")
        }
        val previous = request.history.lastOrNull { it.role == "user" }?.content
            ?.removePrefix("/help")?.trim()
        val query = if (previous != null && FOLLOWUP.containsMatchIn(question)) "$previous\nУточнение: $question" else question
        return Plan(question, scope, query, tools, contextOnly)
    }
    private val DAY = Regex("(?:day\\s*|д(?:ень|ня|не|ню)\\s*)([1-9]\\d*)", RegexOption.IGNORE_CASE)
    private val BRANCH = Regex("ветк|веточ|branch|коммит|commit|detached", RegexOption.IGNORE_CASE)
    private val STATUS = Regex("git\\s+status|статус.*(?:git|репозитор|рабоч)|измен[её]н|изменил|незакоммич|рабоч[её].*дерев", RegexOption.IGNORE_CASE)
    private val FILES = Regex("список.*файл|какие.*файл|покажи.*файл", RegexOption.IGNORE_CASE)
    private val STRUCTURE = Regex("структур|модул|где.*(?:файл|код|провер|реализ|наход)", RegexOption.IGNORE_CASE)
    private val DOCS = Regex("запуст|устро|как\\s.*работ|архитект|api|модел|лимит|очеред|ключ|настрой|документ|readme|rag|зачем|почему", RegexOption.IGNORE_CASE)
    private val FOLLOWUP = Regex("^(?:а|он|она|это|тогда|подробнее)(?:\\s|[?!.,]|$)", RegexOption.IGNORE_CASE)
}

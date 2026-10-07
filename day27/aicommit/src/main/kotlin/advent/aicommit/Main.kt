package advent.aicommit

import advent.localllm.OllamaClient
import advent.localllm.OllamaException
import advent.localllm.commit.CommitWriter
import advent.localllm.commit.DIFF_LIMIT
import advent.localllm.commit.Suggestion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.Locale
import kotlin.system.exitProcess

private val HELP = """
    aicommit — сообщение коммита по staged-изменениям от локальной LLM (Ollama).

    Использование:
      git add <файлы>
      aicommit [-v]

      -v, --verbose   показать тела запроса в Ollama и её ответа
      -h, --help      эта справка

    Переменные окружения:
      AICOMMIT_MODEL    модель Ollama, по умолчанию llama3.2:3b
      OLLAMA_BASE_URL   адрес Ollama, по умолчанию http://localhost:11434
      NO_COLOR          любое значение — без цвета и спиннера
""".trimIndent()

fun main(args: Array<String>) {
    if ("-h" in args || "--help" in args) {
        println(HELP)
        return
    }
    val unknown = args.filterNot { it == "-v" || it == "--verbose" }
    if (unknown.isNotEmpty()) {
        System.err.println("Неизвестные аргументы: ${unknown.joinToString(" ")}\n\n$HELP")
        exitProcess(2)
    }

    val ollama = OllamaClient(
        baseUrl = System.getenv("OLLAMA_BASE_URL")?.trimEnd('/') ?: "http://localhost:11434",
        model = System.getenv("AICOMMIT_MODEL") ?: "llama3.2:3b",
    )
    val code = try {
        run(ollama, verbose = args.isNotEmpty())
    } catch (e: GitException) {
        System.err.println(Term.red(e.message!!))
        1
    } catch (e: OllamaException) {
        System.err.println(Term.red(e.message!!))
        1
    }
    exitProcess(code)
}

private fun run(ollama: OllamaClient, verbose: Boolean): Int {
    val changes = Git.stagedChanges()
    if (changes.files.isEmpty()) {
        System.err.println("Нечего коммитить: в индексе пусто. Сначала git add <файлы>.")
        return 1
    }

    val scope = changes.scope?.let { " · scope $it" }.orEmpty()
    println("📦 ${changes.summary}$scope")
    if (verbose) println(Term.dim(changes.files.joinToString("\n") { "   $it" }))
    val writer = CommitWriter(ollama, changes)
    if (writer.diffTruncated) {
        println(
            "✂️  Дифф ${changes.diff.length.grouped()} символов, модели уйдёт ${DIFF_LIMIT.grouped()}: " +
                "мелкие файлы целиком, от крупных — начало. Список файлов — полностью"
        )
    }

    var suggestion = Term.spinner("${ollama.model} пишет сообщение") { writer.first() }
    show(suggestion, verbose)
    while (true) {
        print("${Term.bold("[y]")} коммит  ${Term.bold("[e]")} правка  ${Term.bold("[r]")} ещё вариант  ${Term.bold("[n]")} выход › ")
        when (readlnOrNull()?.trim()?.lowercase()) {
            "y", "н" -> return Git.commit(suggestion.message, edit = false)
            "e", "у" -> return Git.commit(suggestion.message, edit = true)
            "r", "к" -> {
                print("💡 Подсказка модели (Enter — без неё): ")
                val hint = readlnOrNull().orEmpty()
                suggestion = Term.spinner("${ollama.model} пишет другой вариант") { writer.another(hint) }
                show(suggestion, verbose)
            }
            "n", "т" -> {
                println("Коммит не создан.")
                return 0
            }
            null -> {
                println("\nКоммит не создан: stdin закрыт.")
                return 0
            }
            else -> println(Term.dim("Ожидаю y, e, r или n."))
        }
    }
}

private fun show(suggestion: Suggestion, verbose: Boolean) {
    val r = suggestion.result
    if (verbose) {
        println(Term.dim("→ POST /api/chat\n${pretty(r.rawRequest)}"))
        println(Term.dim("← 200\n${pretty(r.rawResponse)}"))
    }
    println()
    suggestion.message.lines().forEachIndexed { i, line -> println("   " + if (i == 0) Term.bold(Term.green(line)) else line) }
    println()
    val stats = listOfNotNull(
        r.model,
        r.promptTokens?.let { p -> r.answerTokens?.let { a -> "$p → $a ток." } },
        r.tokensPerSecond?.let { "${it.oneDecimal()} ток/с" },
        r.totalSeconds?.let { "${it.oneDecimal()} с" },
    )
    println(Term.dim("   " + stats.joinToString(" · ")))
    println()
}

private val prettyJson = Json { prettyPrint = true }

private fun pretty(body: String): String = prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(body))

private val RU = Locale.forLanguageTag("ru")

private fun Double.oneDecimal() = String.format(RU, "%.1f", this)

private fun Int.grouped() = String.format(RU, "%,d", this)

/** ANSI-цвета и спиннер. Отключаются переменной NO_COLOR (no-color.org). */
private object Term {
    private val enabled = System.getenv("NO_COLOR") == null

    fun bold(s: String) = paint(s, "1")
    fun dim(s: String) = paint(s, "2")
    fun green(s: String) = paint(s, "32")
    fun red(s: String) = paint(s, "31")

    private fun paint(s: String, code: String) = if (enabled) "\u001b[${code}m$s\u001b[0m" else s

    /** Пока идёт [block], крутит спиннер с секундомером; потом стирает строку. */
    fun <T> spinner(label: String, block: () -> T): T {
        if (!enabled) {
            println("⏳ $label…")
            return block()
        }
        val start = System.nanoTime()
        val thread = Thread {
            val frames = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"
            var i = 0
            try {
                while (true) {
                    val seconds = (System.nanoTime() - start) / 1e9
                    print("\r${frames[i++ % frames.length]} $label… ${dim(seconds.oneDecimal() + " с")}")
                    Thread.sleep(100)
                }
            } catch (_: InterruptedException) {
            }
        }
        thread.isDaemon = true
        thread.start()
        try {
            return block()
        } finally {
            thread.interrupt()
            thread.join()
            print("\r\u001b[2K")
        }
    }
}

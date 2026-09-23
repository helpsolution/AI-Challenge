package advent.news.agent

import kotlinx.serialization.json.Json

/**
 * Печать хода работы агента. Смысл в том, чтобы цикл был виден целиком:
 * что модель решила вызвать, что на это ответил MCP-сервер и в каком виде.
 *
 * Режим [raw] добавляет к этому полный обмен с LLM — тот самый JSON, который ушёл
 * в сеть и вернулся оттуда. Отступы добавлены для читаемости, содержимое не меняется.
 */
class Trace(var raw: Boolean = false) {
    fun llmExchange(exchange: LlmExchange) {
        if (!raw) return
        println()
        println("$FRAME┌─ в LLM ──── POST ${exchange.url}")
        exchange.requestHeaders.forEach { (name, value) -> println("$FRAME│  $name: $value") }
        println("$FRAME│")
        printBody(exchange.requestBody)
        println("$FRAME├─ из LLM ─── HTTP ${exchange.status}")
        printBody(exchange.responseBody)
        println("$FRAME└─")
    }

    fun toolCall(call: ToolCall) {
        println("   → LLM вызывает ${call.function.name} ${call.function.arguments}")
    }

    fun toolResult(outcome: ToolOutcome) {
        val mark = if (outcome.isError) "✗" else "←"
        // Сводка — это сотня заголовков: целиком она утопила бы ответ агента, поэтому видно только начало.
        // Полный обмен по-прежнему доступен в режиме /raw.
        val lines = outcome.text.lines()
        val head = lines.take(RESULT_LINES).joinToString("\n        ")
        val hidden = lines.size - RESULT_LINES
        val more = if (hidden > 0) "\n        … и ещё $hidden ${plural(hidden, "строка", "строки", "строк")}" else ""
        println("   $mark MCP: $head$more")
        // structuredContent существует ровно для этого: код читает результат, а не парсит текст.
        outcome.structured?.let {
            val json = COMPACT.encodeToString(it)
            println("     структура: ${if (json.length <= STRUCTURE_CHARS) json else json.take(STRUCTURE_CHARS) + "… (${json.length} символов)"}")
        }
    }

    private fun printBody(body: String) {
        val text = runCatching { PRETTY.encodeToString(PRETTY.parseToJsonElement(body)) }.getOrDefault(body)
        text.lineSequence().forEach { println("$FRAME│ $it") }
    }

    private companion object {
        const val FRAME = "  "
        const val RESULT_LINES = 4
        const val STRUCTURE_CHARS = 160
        val COMPACT = Json { prettyPrint = false }
        val PRETTY = Json { prettyPrint = true }
    }
}

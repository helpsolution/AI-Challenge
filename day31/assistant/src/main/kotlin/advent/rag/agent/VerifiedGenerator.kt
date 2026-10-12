package advent.rag.agent

import advent.rag.grounding.Grounding
import advent.rag.grounding.GroundingChecker
import advent.rag.llm.*
import advent.rag.reranking.RerankedChunk
import advent.rag.trace.Recorder
import org.springframework.stereotype.Component

data class VerifiedReply(val completion: Completion, val checked: Grounding)

@Component
class VerifiedGenerator(private val model: ChatModel, private val checker: GroundingChecker,
    private val prompts: PromptBuilder) {
    fun generate(prompt: List<ChatMessage>, context: List<RerankedChunk>, trace: Recorder): VerifiedReply {
        var completion = model.complete(prompt, prompts.schema, 2200)
        record(completion, trace, "Ответ модели")
        var checked = checker.check(completion, context)
        trace.add("Проверка ссылок и цитат", if (checked.errors.isEmpty()) "ok" else "error", 0, checked)
        // Единственная попытка исправления; неизменные источники остаются единственным доказательством.
        if (checked.status == AnswerStatus.UNVERIFIED) {
            val first = completion
            val repaired = model.complete(prompt + ChatMessage("assistant", first.text) + ChatMessage("user", """
                Проверка отклонила JSON: ${checked.errors.joinToString("; ")}.
                Исправь ответ только по тем же пронумерованным фрагментам.
                Для каждой ссылки [n] нужна дословная цитата из фрагмента n в quotes.
                Убери утверждения и ссылки, которые нельзя подтвердить. Номера источников не меняй.
                Верни исправленный JSON. Если подтверждённого ответа нет, верни unknown.
            """.trimIndent()), prompts.schema, 2200, temperature = 0.0)
            record(repaired, trace, "Исправление ответа · 1 попытка")
            checked = checker.check(repaired, context)
            trace.add("Повторная проверка цитат", if (checked.errors.isEmpty()) "ok" else "error", 0, checked)
            completion = repaired.copy(
                promptTokens = if (first.promptTokens != null && repaired.promptTokens != null) first.promptTokens + repaired.promptTokens else null,
                completionTokens = if (first.completionTokens != null && repaired.completionTokens != null) first.completionTokens + repaired.completionTokens else null)
        }
        return VerifiedReply(completion, checked)
    }

    private fun record(completion: Completion, trace: Recorder, name: String) {
        trace.add(name, "ok", completion.exchange.durationMs, mapOf("model" to completion.model,
            "promptTokens" to completion.promptTokens, "completionTokens" to completion.completionTokens,
            "finishReason" to completion.finishReason, "output" to completion.text))
    }
}

package advent.lab.experiment

import advent.lab.ollama.ChatMessage
import advent.lab.ollama.CreateRequest
import advent.lab.ollama.OllamaClient
import advent.lab.task.TicketTask
import org.springframework.stereotype.Component

data class BuildRequest(val name: String, val config: RunConfig)

/** modelfile — то же самое в виде Modelfile: им можно собрать модель без лаборатории, `ollama create <имя> -f Modelfile`. */
data class BuildResult(val name: String, val from: String, val modelfile: String, val ollama: String)

// Итог настройки — своя модель в Ollama: системный промпт, примеры и параметры записаны в неё, `ollama run` работает сразу.
@Component
class ModelBuilder(private val ollama: OllamaClient, private val task: TicketTask) {

    fun build(request: BuildRequest): BuildResult {
        val name = request.name.trim()
        require(name.isNotEmpty()) { "Введите имя модели, например triage:3b" }
        val config = request.config
        require(config.model.isNotBlank()) { "Выберите базовую модель" }
        val messages = if (config.fewShot) task.shotMessages() else emptyList()
        val parameters = config.options.toMap()
        val response = ollama.create(CreateRequest(
            model = name,
            from = config.model,
            system = config.system.ifBlank { null },
            parameters = parameters,
            messages = messages.ifEmpty { null },
        ))
        return BuildResult(name, config.model, modelfile(config, parameters, messages), response)
    }

    private fun modelfile(config: RunConfig, parameters: Map<String, Any>?, messages: List<ChatMessage>) = buildString {
        appendLine("FROM ${config.model}")
        parameters?.forEach { (key, value) -> appendLine("PARAMETER $key $value") }
        if (config.system.isNotBlank()) appendLine("SYSTEM \"\"\"${config.system}\"\"\"")
        messages.forEach { appendLine("MESSAGE ${it.role} ${quoted(it.content)}") }
    }

    private fun quoted(text: String) = if ('\n' in text) "\"\"\"$text\"\"\"" else text
}

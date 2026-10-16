package advent.files.agent

import tools.jackson.databind.JsonNode

/** Проверяет наличие результатов. Содержательную точность оценивает автор проекта. */
object DocumentationChecks {
    private val sourceExtensions = setOf("kt", "java", "py", "js", "ts", "tsx", "jsx", "vue", "go", "rs", "sql")

    fun warnings(result: JsonNode): List<String> = buildList {
        val read = result.path("readFiles").values().map { it.asString() }
        if (read.none { it.substringAfterLast('.') in sourceExtensions })
            add("Нет прочитанных исходников: сверка с кодом не подтверждена.")
        val changes = result.path("changes").values().toList()
        val report = changes.find { it.path("path").asString() == "docs/sync-report.md" }?.path("content")?.asString()
        if (report.isNullOrBlank()) add("Не сохранён новый или обновлённый docs/sync-report.md с результатами сверки.")
        if (!result.path("patchApplicable").asBoolean()) add("Патч не прошёл git apply --check на исходном снимке: ${result.path("patchError").asString()}")
        if (result.path("sourceChangedPaths").size() > 0)
            add("Исходный проект изменился после создания снимка. Повторите сверку перед применением diff.")
    }
}

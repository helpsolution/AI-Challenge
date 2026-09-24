package advent.pipeline.service

import advent.pipeline.llm.LlmClient
import advent.pipeline.llm.Prompts
import advent.pipeline.web.ArticleInput
import advent.pipeline.web.Source
import advent.pipeline.web.SummarizeRequest
import advent.pipeline.web.Summary
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Шаг 2 конвейера — обработать данные: сделать сводку по статьям.
 *
 * Статьи приходят целиком в теле запроса. Откуда они — из поиска этого же сервиса или из другого,
 * — шаг не знает и знать не должен.
 */
@Service
class SummarizeService(private val llm: LlmClient) {
    private val log = LoggerFactory.getLogger(SummarizeService::class.java)

    fun summarize(request: SummarizeRequest): Summary {
        val query = Validation.query(request.query)
        val items = Validation.items(request.items, "items").mapIndexed { index, item -> validate(item, index) }
        Validation.uniqueIds(items.map { it.id }, "items")

        val raw = llm.complete(Prompts.SUMMARY, prompt(query, items))

        // Модель может сослаться на номер, которого не было в списке. Такая ссылка никуда не ведёт,
        // а шаг save по правилам контракта её отвергнет — поэтому она убирается здесь, у источника.
        val known = items.map { it.id }.toSet()
        val unknown = Validation.citations(raw) - known
        val summary = if (unknown.isEmpty()) {
            raw
        } else {
            log.warn("Модель сослалась на статьи, которых не было на входе: {}", unknown)
            Validation.CITATION.replace(raw) { if (it.groupValues[1] in known) it.value else "" }
        }

        return Summary(
            query = query,
            summary = summary,
            sources = items.map { Source(it.id, it.title, it.link) },
            received = items.size,
            cited = (Validation.citations(summary) intersect known).size,
        )
    }

    /** Проверенная статья: обязательные поля на месте, необязательные обрезаны до разумной длины. */
    private data class Item(
        val id: String,
        val title: String,
        val link: String,
        val author: String?,
        val publishedAt: String?,
        val tags: List<String>,
        val text: String?,
    )

    private fun validate(item: ArticleInput, index: Int): Item {
        val where = "items[$index]"
        return Item(
            id = Validation.id(item.id, where),
            title = Validation.title(item.title, where),
            link = Validation.link(item.link, where),
            author = item.author?.trim()?.take(MAX_AUTHOR_LENGTH)?.takeIf { it.isNotEmpty() },
            publishedAt = item.publishedAt?.trim()?.take(MAX_DATE_LENGTH)?.takeIf { it.isNotEmpty() },
            tags = item.tags.orEmpty().map { it.trim().take(MAX_TAG_LENGTH) }.filter { it.isNotEmpty() }.take(MAX_TAGS),
            text = item.text?.trim()?.take(MAX_TEXT_LENGTH)?.takeIf { it.isNotEmpty() },
        )
    }

    private fun prompt(query: String?, items: List<Item>): String = buildString {
        appendLine(if (query == null) "Свежие статьи Хабра." else "Статьи Хабра по теме «$query».")
        appendLine("Статей: ${items.size}.")
        appendLine()
        items.forEach { item ->
            append("[${item.id}] ${item.title}")
            item.author?.let { append(" · автор $it") }
            if (item.tags.isNotEmpty()) append(" · теги: ${item.tags.joinToString(", ")}")
            item.publishedAt?.let { append(" · $it") }
            appendLine()
            item.text?.let { appendLine("    $it") }
        }
    }

    private companion object {
        const val MAX_AUTHOR_LENGTH = 100
        const val MAX_DATE_LENGTH = 40
        const val MAX_TAGS = 20
        const val MAX_TAG_LENGTH = 100
        /** Больше, чем отдаёт поиск: другой источник может присылать куски длиннее. */
        const val MAX_TEXT_LENGTH = 2000
    }
}

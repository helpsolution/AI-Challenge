package advent.rag.chunking

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StructuralChunkerTest {
    private fun doc(text: String) = Document("intro.md", "Документ", "https://example.com/intro", text)
    private fun checkOffsets(document: Document, chunks: List<Chunk>) {
        assertTrue(chunks.isNotEmpty())
        chunks.forEach { assertEquals(document.text.substring(it.start, it.end), it.text); assertTrue(it.text.isNotBlank()) }
        assertEquals(chunks.size, chunks.map { it.chunkId }.distinct().size)
    }

    @Test fun `definitions stay whole and have their heading path`() {
        val document = doc("# НФТ\n\n## Надёжность\n\nВероятность работы без отказов.\n\nИзмеряется MTBF.\n\n## Доступность\n\nДоля времени в строю.")
        val chunks = StructuralChunker().split(document)
        checkOffsets(document, chunks)
        assertEquals(2, chunks.size)
        assertEquals("НФТ › Надёжность", chunks[0].section)
        assertTrue(chunks[0].text.contains("MTBF"))
        assertTrue(chunks[0].embeddingText.startsWith("Документ\n\nНФТ › Надёжность"))
    }

    @Test fun `glossary entries do not blend into neighbours`() {
        val document = doc("# Глоссарий\n\n- **ADR** - Решение.\n- **C4** - Диаграммы.\n- **MTBF** - Интервал между отказами.")
        val chunks = StructuralChunker().split(document)
        checkOffsets(document, chunks)
        assertEquals(3, chunks.size)
        assertTrue(chunks.all { it.text.count { c -> c == '\n' } == 0 })
    }

    @Test fun `formula and its variable definitions stay in the same section chunk`() {
        val document = doc("# Надёжность\n\n## Формула\n\nВ устойчивом режиме:\n\n`A = MTBF / (MTBF + MTTR)`\n\nГде:\n\n- **MTBF** - время между отказами.\n- **MTTR** - время ремонта.\n- **A** - доступность.")
        val chunks = StructuralChunker().split(document)
        assertEquals(1, chunks.size)
        assertTrue(chunks.single().content.contains("A = MTBF"))
        assertTrue(chunks.single().content.contains("время ремонта"))
    }

    @Test fun `split lists keep introductory phrase and whole items`() {
        val intro = "Держите вместе следующие метрики:"
        val items = (1..12).map { "- Показатель $it описывает важную характеристику системы." }
        val document = doc("# Метрики\n\n$intro\n\n" + items.joinToString("\n"))
        val chunks = StructuralChunker(120, 200).split(document)
        checkOffsets(document, chunks)
        val listChunks = chunks.filter { it.text.trimStart().startsWith('-') }
        assertTrue(listChunks.isNotEmpty())
        listChunks.forEach { assertTrue(it.content.startsWith(intro)); assertTrue(it.content.length <= 200) }
        items.forEach { item -> assertEquals(1, chunks.count { it.text.contains(item) }) }
    }

    @Test fun `long tables retain their header and all rows`() {
        val header = "| Метрика | Значение |\n| --- | --- |\n"
        val rows = (1..30).map { "| Показатель $it | Значение $it |" }
        val document = doc("# Метрики\n\n" + header + rows.joinToString("\n"))
        val chunks = StructuralChunker(160, 240).split(document)
        checkOffsets(document, chunks)
        assertTrue(chunks.size > 1)
        chunks.forEach { assertTrue(it.content.contains("| Метрика | Значение |")); assertTrue(it.content.length <= 240) }
        rows.forEach { row -> assertEquals(1, chunks.count { it.text.contains(row) }) }
    }

    @Test fun `headings inside fenced code are not sections`() {
        val document = doc("# Пример\n\n```python\n# Это комментарий\nprint('hello')\n```\n\n## Вывод\n\nПривет.")
        val chunks = StructuralChunker().split(document)
        assertEquals(2, chunks.size)
        assertEquals("Пример", chunks[0].section)
        assertTrue(chunks[0].content.contains("# Это комментарий"))
    }

    @Test fun `long fences are closed and reopened with language`() {
        val document = doc("# Код\n\n~~~python\n" + (1..30).joinToString("\n") { "print('line $it')" } + "\n~~~")
        val chunks = StructuralChunker(100, 140).split(document)
        checkOffsets(document, chunks)
        assertTrue(chunks.size > 1)
        chunks.forEach { assertTrue(it.content.startsWith("~~~python\n")); assertTrue(it.content.endsWith("\n~~~")); assertTrue(it.content.length <= 140) }
    }

    @Test fun `sentence boundaries and unicode survive oversized paragraphs`() {
        val sentence = "Это законченное предложение 😀. "
        val document = doc("# Текст\n\n" + sentence.repeat(60))
        val chunks = StructuralChunker(100, 140).split(document)
        checkOffsets(document, chunks)
        chunks.forEach { assertTrue(it.text.trimEnd().endsWith('.')); assertTrue(it.content.length <= 140) }
        assertEquals(document.text.count { it.isHighSurrogate() }, chunks.sumOf { it.text.count { c -> c.isHighSurrogate() } })
    }
}

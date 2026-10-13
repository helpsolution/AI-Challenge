package advent.review.rag

import advent.review.chunking.Chunk
import advent.review.chunking.StructuralChunker
import advent.review.diff.DiffLine
import advent.review.embedding.EmbeddingClient
import advent.review.embedding.EmbeddingException
import advent.review.review.ChangedFile
import advent.review.review.ReviewProperties
import org.springframework.stereotype.Component
import kotlin.math.sqrt

data class Retrieval(
    val scopes: List<String>,
    val fragments: List<Fragment>,
    val docChunks: Int,
    val codeChunks: Int,
    val modules: List<String>,
    val warning: String?,
)

/**
 * Контекст для ревью в порядке важности: правила ревью целиком, документация по смыслу изменений,
 * использования изменённых символов в неизменённых файлах, похожий код.
 */
@Component
class ContextRetriever(private val corpus: Corpus, private val embeddings: EmbeddingClient, private val properties: ReviewProperties) {
    private val docChunker = StructuralChunker()
    private val codeChunker = CodeChunker()

    fun retrieve(files: List<ChangedFile>): Retrieval {
        val scopes = corpus.scopes(files.map { it.diff.path })
        val modules = corpus.modules(files.map { it.diff.path })
        val rules = corpus.rules()?.let(docChunker::split).orEmpty().map(::RuleFragment)
        val docs = corpus.documents(scopes).flatMap(docChunker::split)
        // Новые версии изменённых файлов модель видит целиком — их куски в контекст не дублируем.
        val shown = files.filter { it.lines != null }.map { it.diff.path }.toSet()
        val code = corpus.code(modules).filter { it.path !in shown }.flatMap { codeChunker.split(it.path, it.text) }
        val usages = usages(files, code)
        val used = usages.map { it.chunk }.toSet()
        val (similarDocs, similarCode, warning) = try {
            semantic(files, docs, code.filter { it !in used })
        } catch (e: EmbeddingException) {
            Triple(emptyList(), emptyList(), "Семантический поиск недоступен, контекст собран без него: ${e.message}")
        }
        return Retrieval(scopes, rules + similarDocs + usages + similarCode, docs.size, code.size, modules, warning)
    }

    // Символы, объявленные в добавленных и удалённых строках, и куски неизменённого кода, где они встречаются.
    // Сначала редкие символы: чем меньше мест использования, тем точнее они описывают затронутый код.
    private fun usages(files: List<ChangedFile>, code: List<CodeChunk>): List<UsageFragment> {
        val symbols = files.flatMap { file -> file.diff.hunks.flatMap { it.lines } }
            .filter { it.kind != DiffLine.Kind.CONTEXT }
            .flatMap { line -> declared(line.text) }
            .distinct()
        return symbols
            .map { symbol -> Regex("\\b${Regex.escape(symbol)}\\b").let { word -> symbol to code.filter { word.containsMatchIn(it.text) } } }
            .filter { it.second.isNotEmpty() }
            .sortedBy { it.second.size }
            .flatMap { (symbol, chunks) -> chunks.map { UsageFragment(it, symbol) } }
            .distinctBy { it.chunk }
            .take(properties.usagesLimit)
    }

    private fun declared(line: String): List<String> {
        val members = MEMBER.findAll(line).map { it.groupValues[1] }
        // Свойство — только на уровне класса или файла; локальные val внутри функций в других файлах не встречаются.
        val properties = PROPERTY.find(line)?.groupValues?.get(1)?.let(::sequenceOf).orEmpty()
        return (members + properties).filter { it.length >= 4 && it !in COMMON }.toList()
    }

    private fun semantic(files: List<ChangedFile>, docs: List<Chunk>, code: List<CodeChunk>): Triple<List<DocFragment>, List<CodeFragment>, String?> {
        val queries = files.filter { !it.diff.binary }.map { file ->
            val changed = file.diff.hunks.flatMap { it.lines }.filter { it.kind != DiffLine.Kind.CONTEXT }.joinToString("\n") { it.text.trim() }
            // Запрос — путь и изменённые строки; длиннее окна nomic-embed-text Ollama его не примет.
            (file.diff.path + "\n" + changed).take(QUERY_CHARS)
        }
        if (queries.isEmpty()) return Triple(emptyList(), emptyList(), null)
        val queryVectors = queries.map(embeddings::embedQuery)
        val docVectors = embed(docs.map { it.embeddingText })
        val codeVectors = embed(code.map { it.embeddingText })
        fun best(vector: FloatArray) = queryVectors.maxOf { cosine(it, vector) }
        val similarDocs = docs.zip(docVectors) { chunk, vector -> DocFragment(chunk, best(vector)) }
            .sortedByDescending { it.score }.take(properties.docsLimit)
        val similarCode = code.zip(codeVectors) { chunk, vector -> CodeFragment(chunk, best(vector)) }
            .sortedByDescending { it.score }.take(properties.codeLimit)
        return Triple(similarDocs, similarCode, null)
    }

    private fun embed(texts: List<String>): List<FloatArray> = texts.chunked(BATCH).flatMap(embeddings::embedDocuments)

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "Векторы разной длины: ${a.size} и ${b.size}" }
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            normA += a[i].toDouble() * a[i]
            normB += b[i].toDouble() * b[i]
        }
        return if (normA == 0.0 || normB == 0.0) 0.0 else dot / (sqrt(normA) * sqrt(normB))
    }

    private companion object {
        const val BATCH = 16
        const val QUERY_CHARS = 1500
        val MEMBER = Regex("""\b(?:fun|class|object|interface|def|function)\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)""")
        val PROPERTY = Regex("""^\s{0,4}(?:(?:private|internal|public|protected|override|open|const|lateinit)\s+)*(?:val|var)\s+(\w+)""")
        val COMMON = setOf("main", "init", "invoke", "toString", "equals", "hashCode", "value", "name")
    }
}

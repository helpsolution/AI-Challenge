package advent.rag.document

import org.yaml.snakeyaml.Yaml

/** Документ корпуса. Шапка YAML в текст не входит: её поля — метаданные, а [text] — то, что режут на чанки. */
data class Document(
    /** Путь к файлу. Из него же собирается chunk_id. */
    val source: String,
    val title: String,
    /** Ссылка на оригинал; null, если в шапке её нет. */
    val url: String?,
    /** Текст без шапки. Границы чанков start/end отсчитываются от него. */
    val text: String,
) {
    /** chunk_id — путь и номер чанка: та же нарезка того же файла даёт те же id. */
    fun chunkId(index: Int) = "$source#$index"

    companion object {
        /** Шапка — блок между строками `---` в самом начале файла. */
        private val FRONT_MATTER = Regex("""\A---\r?\n(.*?)\r?\n---[ \t]*(?:\r?\n|\z)""", RegexOption.DOT_MATCHES_ALL)

        fun parse(source: String, content: String): Document {
            val frontMatter = FRONT_MATTER.find(content)
            val fields: Map<String, Any?> = frontMatter?.let { Yaml().load(it.groupValues[1]) } ?: emptyMap()
            return Document(
                source = source,
                // У файла без шапки названием будет имя файла.
                title = fields["title"]?.toString() ?: source.substringAfterLast('/'),
                url = fields["url"]?.toString(),
                text = frontMatter?.let { content.substring(it.range.last + 1) } ?: content,
            )
        }
    }
}

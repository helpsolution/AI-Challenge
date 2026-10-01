package advent.rag.chunking

import org.yaml.snakeyaml.Yaml

data class Document(
    val source: String,
    val title: String,
    val url: String?,
    val text: String,
) {
    fun chunkId(index: Int) = "$source#$index"

    companion object {
        private val FRONT_MATTER = Regex("""\A---\r?\n(.*?)\r?\n---[ \t]*(?:\r?\n|\z)""", RegexOption.DOT_MATCHES_ALL)

        fun parse(source: String, content: String): Document {
            val frontMatter = FRONT_MATTER.find(content)
            val fields: Map<String, Any?> = frontMatter?.let { Yaml().load(it.groupValues[1]) } ?: emptyMap()
            return Document(
                source = source,
                title = fields["title"]?.toString() ?: source.substringAfterLast('/'),
                url = fields["url"]?.toString(),
                text = frontMatter?.let { content.substring(it.range.last + 1) } ?: content,
            )
        }
    }
}

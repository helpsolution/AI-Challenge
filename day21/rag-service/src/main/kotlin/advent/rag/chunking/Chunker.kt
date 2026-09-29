package advent.rag.chunking

import advent.rag.document.Document


interface Chunker<T> {

    fun split(document: Document): List<T>
}

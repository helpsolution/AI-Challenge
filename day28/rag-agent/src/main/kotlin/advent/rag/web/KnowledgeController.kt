package advent.rag.web

import advent.rag.knowledge.Indexer
import advent.rag.knowledge.KnowledgeBase
import advent.rag.knowledge.RebuildReport
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RequestParam
import advent.rag.chunking.Chunk

@RestController
@RequestMapping("/api/knowledge")
class KnowledgeController(private val knowledge: KnowledgeBase, private val indexer: Indexer) {

    @GetMapping
    fun meta(): Map<String, String> = knowledge.meta

    @GetMapping("/status")
    fun status(): Map<String, Any?> = mapOf("ready" to knowledge.meta.isNotEmpty(), "meta" to knowledge.meta, "error" to indexer.lastError)

    @GetMapping("/chunks")
    fun chunks(@RequestParam(required = false) source: String?, @RequestParam(defaultValue = "0") offset: Int,
               @RequestParam(defaultValue = "30") limit: Int): List<Chunk> {
        require(offset >= 0 && limit in 1..100)
        return knowledge.chunks().filter { source == null || it.source == source }.drop(offset).take(limit)
    }

    @PostMapping("/rebuild")
    fun rebuild(): RebuildReport = indexer.rebuild()
}

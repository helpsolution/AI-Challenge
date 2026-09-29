package advent.rag.web

import advent.rag.knowledge.Indexer
import advent.rag.knowledge.KnowledgeBase
import advent.rag.knowledge.RebuildReport
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/knowledge")
class KnowledgeController(private val knowledge: KnowledgeBase, private val indexer: Indexer) {

    /** Что в базе знаний: модель, нарезка, число документов и чанков. Пустой объект — базы ещё нет. */
    @GetMapping
    fun meta(): Map<String, String> = knowledge.meta

    /** Пересобрать базу из data/corpus: нужно, если документы или параметры нарезки поменялись. */
    @PostMapping("/rebuild")
    fun rebuild(): RebuildReport = indexer.rebuild()
}

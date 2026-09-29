package advent.rag.web

import advent.rag.chunking.FixedSizeChunk
import advent.rag.chunking.FixedSizeChunker
import advent.rag.chunking.StructuralChunk
import advent.rag.chunking.StructuralChunker
import advent.rag.index.FixedSizeIndex
import advent.rag.index.IndexContents
import advent.rag.index.Indexer
import advent.rag.index.RebuildReport
import advent.rag.index.StructuralIndex
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/index")
@Tag(name = "Индекс", description = "Третий кубик: корпус → чанки обеих стратегий → эмбеддинги → две базы SQLite")
class IndexController(
    private val indexer: Indexer,
    private val fixedSizeIndex: FixedSizeIndex,
    private val structuralIndex: StructuralIndex,
) {

    @Operation(
        summary = "Пересобрать оба индекса",
        description = "Читает документы из data/corpus, режет их обеими стратегиями, считает эмбеддинги и заново пишет " +
            "data/index/fixed-size.db и data/index/structural.db. Занимает десятки секунд.",
    )
    @PostMapping("/rebuild")
    fun rebuild(
        @Parameter(description = "Размер чанка фиксированной стратегии, символов.")
        @RequestParam(defaultValue = "${FixedSizeChunker.DEFAULT_CHUNK_SIZE}")
        chunkSize: Int,
        @Parameter(description = "Перекрытие фиксированной стратегии, символов.")
        @RequestParam(defaultValue = "${FixedSizeChunker.DEFAULT_OVERLAP}")
        overlap: Int,
        @Parameter(description = "Предел длины чанка структурной стратегии, символов.")
        @RequestParam(defaultValue = "${StructuralChunker.DEFAULT_MAX_CHUNK_SIZE}")
        maxChunkSize: Int,
    ): RebuildReport = indexer.rebuild(chunkSize, overlap, maxChunkSize)

    @Operation(summary = "Что лежит в fixed-size.db", description = "Метаданные индекса и чанки, без векторов.")
    @GetMapping("/fixed-size")
    fun fixedSize(): IndexContents<FixedSizeChunk> = fixedSizeIndex.read()

    @Operation(summary = "Что лежит в structural.db", description = "Метаданные индекса и чанки, без векторов.")
    @GetMapping("/structural")
    fun structural(): IndexContents<StructuralChunk> = structuralIndex.read()
}

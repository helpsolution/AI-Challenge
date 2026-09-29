package advent.rag.web

import advent.rag.chunking.FixedSizeChunk
import advent.rag.chunking.FixedSizeChunker
import advent.rag.chunking.StructuralChunk
import advent.rag.chunking.StructuralChunker
import advent.rag.document.Document
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.parameters.RequestBody as ApiRequestBody

/**
 * Тело — содержимое файла как есть, а не JSON: в Swagger можно вставить документ целиком, вместе с шапкой YAML.
 * Шапка разбирается в title и url, в чанки идёт только текст под ней.
 */
@RestController
@RequestMapping("/api/chunking")
@Tag(name = "Чанкинг", description = "Песочница второго кубика: как документ режется на куски перед эмбеддингом")
class ChunkingController {

    @Operation(
        summary = "По фиксированному размеру",
        description = "Режет текст окном в chunkSize символов с шагом chunkSize − overlap. Граница проходит вслепую, " +
            "в том числе посреди слова. Попробуйте overlap = 0 и сравните стыки чанков.",
    )
    @ApiRequestBody(
        content = [Content(mediaType = MediaType.TEXT_PLAIN_VALUE, examples = [ExampleObject(name = "Про RAG", value = PLAIN_SAMPLE)])],
    )
    @PostMapping("/fixed-size", consumes = [MediaType.TEXT_PLAIN_VALUE])
    fun fixedSize(
        @RequestBody content: String,
        @Parameter(description = SOURCE_DESCRIPTION)
        @RequestParam(defaultValue = DEFAULT_SOURCE)
        source: String,
        @Parameter(description = "Размер чанка в символах.")
        @RequestParam(defaultValue = "${FixedSizeChunker.DEFAULT_CHUNK_SIZE}")
        chunkSize: Int,
        @Parameter(description = "Сколько символов конца чанка повторить в начале следующего, от 0 до chunkSize − 1.")
        @RequestParam(defaultValue = "${FixedSizeChunker.DEFAULT_OVERLAP}")
        overlap: Int,
    ): ChunkingResponse<FixedSizeChunk> =
        ChunkingResponse(FixedSizeChunker(chunkSize, overlap).split(Document.parse(source, content)))

    @Operation(
        summary = "По структуре",
        description = "Режет Markdown по заголовкам: раздел — чанк, путь заголовков — в поле section. Раздел длиннее " +
            "maxChunkSize делится по абзацам. Попробуйте maxChunkSize = 200 на примере.",
    )
    @ApiRequestBody(
        content = [Content(mediaType = MediaType.TEXT_PLAIN_VALUE, examples = [ExampleObject(name = "Markdown", value = MARKDOWN_SAMPLE)])],
    )
    @PostMapping("/structural", consumes = [MediaType.TEXT_PLAIN_VALUE])
    fun structural(
        @RequestBody content: String,
        @Parameter(description = SOURCE_DESCRIPTION)
        @RequestParam(defaultValue = DEFAULT_SOURCE)
        source: String,
        @Parameter(description = "Предел длины чанка в символах. Раздел короче предела остаётся целым.")
        @RequestParam(defaultValue = "${StructuralChunker.DEFAULT_MAX_CHUNK_SIZE}")
        maxChunkSize: Int,
    ): ChunkingResponse<StructuralChunk> =
        ChunkingResponse(StructuralChunker(maxChunkSize).split(Document.parse(source, content)))

    private companion object {
        const val DEFAULT_SOURCE = "swagger"
        const val SOURCE_DESCRIPTION = "Путь к файлу документа: из него собирается chunk_id, а без шапки — и title. " +
            "Например, data/corpus/intro.md."
    }
}

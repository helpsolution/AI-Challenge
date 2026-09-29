package advent.rag.web

import advent.rag.embedding.EmbeddingClient
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.parameters.RequestBody as ApiRequestBody

@RestController
@RequestMapping("/api")
@Tag(name = "Эмбеддинги", description = "Песочница первого кубика: какие векторы модель ставит в соответствие текстам")
class EmbeddingController(private val embeddings: EmbeddingClient) {

    @Operation(
        summary = "Текст → вектор",
        description = "Считает эмбеддинг каждого текста локальной моделью Ollama. Текст длиннее контекста модели " +
            "не обрезается, а возвращается ошибкой 400.",
    )
    @ApiRequestBody(
        content = [
            Content(
                examples = [
                    ExampleObject(
                        name = "Кот, собака, машина",
                        value = """{"texts": ["кот", "собака", "автомобиль"]}""",
                    ),
                    ExampleObject(
                        name = "С префиксами nomic",
                        description = "nomic-embed-text обучали с префиксами задачи: документы — search_document:, " +
                            "вопросы — search_query:. Сравните векторы с префиксами и без.",
                        value = """{"texts": ["search_query: Когда основали SpaceX?", """ +
                            """"search_document: SpaceX was founded in 2002"]}""",
                    ),
                ],
            ),
        ],
    )
    @PostMapping("/embeddings")
    fun embed(@RequestBody request: EmbeddingRequest): EmbeddingResponse =
        EmbeddingResponse.of(request.texts, embeddings.embed(request.texts))
}

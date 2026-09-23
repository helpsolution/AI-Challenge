package advent.news.web

import advent.news.service.DigestService
import advent.news.service.SubscriptionService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
@Tag(name = "Новости", description = "Подписки, которые сервис сам обновляет по расписанию, и сводка по собранному")
class NewsController(
    private val subscriptions: SubscriptionService,
    private val digests: DigestService,
) {
    @GetMapping("/sources")
    @Operation(summary = "Каталог лент", description = "Ленты изданий, на которые можно подписаться целиком.")
    fun sources(): List<Source> = subscriptions.sources()

    @GetMapping("/subscriptions")
    @Operation(
        summary = "Подписки",
        description = "Все подписки с расписанием и итогом последнего сбора: когда забирали, сколько нового, была ли ошибка.",
    )
    fun list(): List<Subscription> = subscriptions.list()

    @PostMapping("/subscriptions/feeds")
    @Operation(
        summary = "Подписаться на ленту издания",
        description = "Первый сбор делается сразу, дальше — по расписанию. Если подписка уже есть, у неё меняется интервал.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Подписка создана, первый сбор сделан"),
        ApiResponse(responseCode = "200", description = "Подписка уже была, интервал обновлён"),
        ApiResponse(
            responseCode = "400",
            description = "Неизвестная лента или интервал вне 1..1440",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun subscribeFeed(@RequestBody request: FeedSubscriptionRequest): ResponseEntity<SubscribeResult> =
        subscriptions.subscribeFeed(request.source, request.everyMinutes).toResponse()

    @PostMapping("/subscriptions/topics")
    @Operation(
        summary = "Подписаться на тему",
        description = "Тема ищется по всем изданиям через Google News за последние сутки. " +
            "Первый сбор делается сразу, дальше — по расписанию.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Подписка создана, первый сбор сделан"),
        ApiResponse(responseCode = "200", description = "Подписка уже была, интервал обновлён"),
        ApiResponse(
            responseCode = "400",
            description = "Тема короче 2 или длиннее 100 символов, интервал вне 1..1440",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun subscribeTopic(@RequestBody request: TopicSubscriptionRequest): ResponseEntity<SubscribeResult> =
        subscriptions.subscribeTopic(request.query, request.everyMinutes).toResponse()

    @DeleteMapping("/subscriptions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Отписаться", description = "Удаляет подписку вместе с собранными по ней новостями.")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Подписка удалена"),
        ApiResponse(
            responseCode = "404",
            description = "Такой подписки нет",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun unsubscribe(@PathVariable id: Long) = subscriptions.unsubscribe(id)

    @GetMapping("/digest")
    @Operation(
        summary = "Сводка за период",
        description = "Сколько новостей вышло за последние N минут (или собрано после курсора afterId), " +
            "из каких изданий и по каким подпискам, и сами заголовки без повторов, новые сверху. " +
            "Поле cursor ответа — начало следующей сводки.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Сводка, возможно пустая"),
        ApiResponse(
            responseCode = "400",
            description = "Период вне 1..10080 минут, заданы и minutes, и afterId, или limit вне 1..300",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "Подписки из subscriptionId нет",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun digest(
        @Parameter(description = "За сколько последних минут, по времени публикации. По умолчанию 60", example = "60")
        @RequestParam(required = false) minutes: Int?,
        @Parameter(description = "Вместо minutes: всё, что собрано после этого курсора (поле cursor прошлой сводки)")
        @RequestParam(required = false) afterId: Long?,
        @Parameter(description = "Только новости, где в заголовке есть слово, начинающееся с этого текста", example = "ИИ")
        @RequestParam(required = false) query: String?,
        @Parameter(description = "Только новости одной подписки")
        @RequestParam(required = false) subscriptionId: Long?,
        @Parameter(description = "Сколько заголовков вернуть, 1..300", example = "100")
        @RequestParam(defaultValue = "100") limit: Int,
    ): Digest = digests.digest(minutes, afterId, query, subscriptionId, limit)

    private fun SubscribeResult.toResponse(): ResponseEntity<SubscribeResult> =
        ResponseEntity.status(if (created) HttpStatus.CREATED else HttpStatus.OK).body(this)
}

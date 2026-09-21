package advent.day16.weather.web

import advent.day16.weather.service.WeatherService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
@Tag(name = "Погода", description = "Поиск городов и погода по названию города")
class WeatherController(private val service: WeatherService) {
    @GetMapping("/cities")
    @Operation(
        summary = "Найти город",
        description = "Геокодинг по названию: координаты, страна, регион и часовой пояс. " +
            "Помогает снять неоднозначность — Москва есть и в России, и в США.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Список найденных городов, возможно пустой"),
        ApiResponse(
            responseCode = "400",
            description = "Пустой query или limit вне диапазона 1..10",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "502",
            description = "Open-Meteo недоступен",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun cities(
        @Parameter(description = "Название города", example = "Москва")
        @RequestParam query: String,
        @Parameter(description = "Сколько вариантов вернуть, 1..10", example = "5")
        @RequestParam(defaultValue = "5") limit: Int,
    ): List<City> = service.searchCities(query, limit)

    @GetMapping("/weather/current")
    @Operation(
        summary = "Текущая погода",
        description = "Температура, ощущаемая температура, влажность, ветер и состояние неба.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Погода найдена"),
        ApiResponse(
            responseCode = "400",
            description = "Пустой параметр city",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "Город не найден",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "502",
            description = "Open-Meteo недоступен",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun current(
        @Parameter(description = "Название города", example = "Москва")
        @RequestParam city: String,
    ): CurrentWeather = service.currentWeather(city)

    @GetMapping("/weather/forecast")
    @Operation(
        summary = "Прогноз по дням",
        description = "Минимальная и максимальная температура, осадки и состояние неба на 1..7 дней.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Прогноз построен"),
        ApiResponse(
            responseCode = "400",
            description = "Пустой city или days вне диапазона 1..7",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "Город не найден",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "502",
            description = "Open-Meteo недоступен",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun forecast(
        @Parameter(description = "Название города", example = "Берлин")
        @RequestParam city: String,
        @Parameter(description = "На сколько дней вперед, 1..7", example = "3")
        @RequestParam(defaultValue = "3") days: Int,
    ): Forecast = service.forecast(city, days)
}

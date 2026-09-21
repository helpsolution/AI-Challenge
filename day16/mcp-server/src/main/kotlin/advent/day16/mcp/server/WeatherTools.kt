package advent.day16.mcp.server

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Три инструмента поверх REST API сервиса погоды.
 * Описания и схемы аргументов — это то, по чему модель сама поймет, когда и как их звать.
 */
fun Server.registerWeatherTools(api: WeatherApi) {
    addTool(
        name = "search_city",
        description = """
            Найти город по названию и получить его координаты, страну и часовой пояс.
            Полезно, когда название неоднозначное: например, Москва есть и в России, и в США.
        """.trimIndent(),
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Название города, например \"Москва\" или \"Berlin\"")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Сколько вариантов вернуть, от 1 до 10")
                    put("minimum", 1)
                    put("maximum", 10)
                    put("default", 5)
                }
            },
            required = listOf("query"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = true),
    ) { request ->
        val query = request.stringArgument("query")
            ?: return@addTool failure("Не передан обязательный аргумент query")
        val limit = request.intArgument("limit") ?: DEFAULT_CITY_LIMIT

        respond {
            val cities = api.searchCities(query, limit)
            if (cities.isEmpty()) {
                "По запросу \"$query\" ничего не найдено"
            } else {
                cities.joinToString("\n") { city ->
                    "${city.title()} — ${city.latitude}, ${city.longitude} (${city.timezone})"
                }
            }
        }
    }

    addTool(
        name = "get_current_weather",
        description = "Текущая погода в городе: температура, ощущаемая температура, влажность, ветер и состояние неба.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("city") {
                    put("type", "string")
                    put("description", "Название города, например \"Москва\"")
                }
            },
            required = listOf("city"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = true),
    ) { request ->
        val city = request.stringArgument("city")
            ?: return@addTool failure("Не передан обязательный аргумент city")

        respond {
            val weather = api.currentWeather(city)
            """
                ${weather.city.title()}, ${weather.observedAt}
                ${weather.temperature} °C (ощущается как ${weather.feelsLike} °C), ${weather.condition}
                Влажность ${weather.humidity}%, ветер ${weather.windSpeed} км/ч
            """.trimIndent()
        }
    }

    addTool(
        name = "get_forecast",
        description = "Прогноз погоды по дням: минимальная и максимальная температура, осадки и состояние неба.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("city") {
                    put("type", "string")
                    put("description", "Название города, например \"Москва\"")
                }
                putJsonObject("days") {
                    put("type", "integer")
                    put("description", "На сколько дней вперед нужен прогноз, от 1 до 7")
                    put("minimum", 1)
                    put("maximum", 7)
                    put("default", 3)
                }
            },
            required = listOf("city"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = true),
    ) { request ->
        val city = request.stringArgument("city")
            ?: return@addTool failure("Не передан обязательный аргумент city")
        val days = request.intArgument("days") ?: DEFAULT_FORECAST_DAYS

        respond {
            val forecast = api.forecast(city, days)
            val lines = forecast.days.map { day ->
                "${day.date}: от ${day.minTemperature} до ${day.maxTemperature} °C, " +
                    "${day.condition}, осадки ${day.precipitation} мм"
            }
            (listOf("Прогноз для города ${forecast.city.title()}:") + lines).joinToString("\n")
        }
    }
}

private const val DEFAULT_CITY_LIMIT = 5
private const val DEFAULT_FORECAST_DAYS = 3

private fun CallToolRequest.stringArgument(name: String): String? =
    arguments?.get(name)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

private fun CallToolRequest.intArgument(name: String): Int? =
    arguments?.get(name)?.jsonPrimitive?.intOrNull

/** Ошибку бизнес-сервиса отдаем как ошибку инструмента, а не как падение соединения. */
private inline fun respond(block: () -> String): CallToolResult =
    try {
        CallToolResult(content = listOf(TextContent(block())))
    } catch (e: WeatherApiException) {
        failure(e.message ?: "Сервис погоды недоступен")
    }

private fun failure(message: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(message)), isError = true)

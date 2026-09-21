package advent.day16.mcp.server

import kotlinx.serialization.Serializable

/** Ответы сервиса погоды. Типы описаны один раз здесь — инструменты работают уже с готовыми объектами. */
@Serializable
data class City(
    val name: String,
    val country: String,
    val region: String = "",
    val latitude: Double,
    val longitude: Double,
    val timezone: String = "",
) {
    /** "Москва, Россия", но "Москва, Айдахо, США" — регион показываем только когда он уточняет название. */
    fun title(): String = listOfNotNull(name, region.takeIf { it.isNotBlank() && it != name }, country)
        .joinToString(", ")
}

@Serializable
data class CurrentWeather(
    val city: City,
    val observedAt: String,
    val temperature: Double,
    val feelsLike: Double,
    val humidity: Int,
    val windSpeed: Double,
    val condition: String,
)

@Serializable
data class Forecast(
    val city: City,
    val days: List<ForecastDay>,
)

@Serializable
data class ForecastDay(
    val date: String,
    val minTemperature: Double,
    val maxTemperature: Double,
    val precipitation: Double,
    val condition: String,
)

/** Тело ошибки сервиса погоды. */
@Serializable
data class ApiError(val message: String)

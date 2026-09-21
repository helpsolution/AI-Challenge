package advent.day16.weather.service

import advent.day16.weather.openmeteo.OpenMeteoClient
import advent.day16.weather.web.City
import advent.day16.weather.web.CurrentWeather
import advent.day16.weather.web.Forecast
import org.springframework.stereotype.Service

/** Бизнес-логика сервиса: разрешение города по названию и выдача погоды. */
@Service
class WeatherService(private val client: OpenMeteoClient) {
    fun searchCities(query: String, limit: Int): List<City> {
        require(query.isNotBlank()) { "Параметр query не может быть пустым" }
        require(limit in 1..MAX_CITIES) { "Параметр limit должен быть от 1 до $MAX_CITIES" }
        return client.searchCities(query.trim(), limit)
    }

    fun currentWeather(city: String): CurrentWeather = client.currentWeather(resolve(city))

    fun forecast(city: String, days: Int): Forecast {
        require(days in 1..MAX_FORECAST_DAYS) { "Прогноз доступен на срок от 1 до $MAX_FORECAST_DAYS дней" }
        return client.forecast(resolve(city), days)
    }

    private fun resolve(city: String): City {
        require(city.isNotBlank()) { "Параметр city не может быть пустым" }
        return client.searchCities(city.trim(), 1).firstOrNull()
            ?: throw CityNotFoundException("Город \"$city\" не найден")
    }

    private companion object {
        const val MAX_CITIES = 10
        const val MAX_FORECAST_DAYS = 7
    }
}

class CityNotFoundException(message: String) : RuntimeException(message)

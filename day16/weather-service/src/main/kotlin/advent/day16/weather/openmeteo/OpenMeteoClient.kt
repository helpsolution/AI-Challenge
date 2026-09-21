package advent.day16.weather.openmeteo

import advent.day16.weather.config.OpenMeteoProperties
import advent.day16.weather.web.City
import advent.day16.weather.web.CurrentWeather
import advent.day16.weather.web.Forecast
import advent.day16.weather.web.ForecastDay
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8

/** Клиент Open-Meteo: геокодинг по названию города и прогноз по координатам. Ключ не нужен. */
@Component
class OpenMeteoClient(
    private val properties: OpenMeteoProperties,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun searchCities(query: String, limit: Int): List<City> {
        val url = "${properties.geocodingUrl}?name=${encode(query)}&count=$limit" +
            "&language=${properties.language}&format=json"
        val root = getJson(url)
        val results = root.path("results")
        if (!results.isArray) return emptyList()
        return results.values().map { it.toCity() }
    }

    fun currentWeather(city: City): CurrentWeather {
        val root = getJson(forecastUrl(city, days = 1))
        val current = root.path("current")
        return CurrentWeather(
            city = city,
            observedAt = current.path("time").asString(),
            temperature = current.path("temperature_2m").asDouble(),
            feelsLike = current.path("apparent_temperature").asDouble(),
            humidity = current.path("relative_humidity_2m").asInt(),
            windSpeed = current.path("wind_speed_10m").asDouble(),
            condition = WeatherCodes.describe(current.path("weather_code").asInt()),
        )
    }

    fun forecast(city: City, days: Int): Forecast {
        val root = getJson(forecastUrl(city, days))
        val daily = root.path("daily")
        val dates = daily.path("time")
        val maxTemperatures = daily.path("temperature_2m_max")
        val minTemperatures = daily.path("temperature_2m_min")
        val precipitation = daily.path("precipitation_sum")
        val codes = daily.path("weather_code")

        val forecastDays = dates.indices().map { index ->
            ForecastDay(
                date = dates.path(index).asString(),
                minTemperature = minTemperatures.path(index).asDouble(),
                maxTemperature = maxTemperatures.path(index).asDouble(),
                precipitation = precipitation.path(index).asDouble(),
                condition = WeatherCodes.describe(codes.path(index).asInt()),
            )
        }
        return Forecast(city = city, days = forecastDays)
    }

    private fun forecastUrl(city: City, days: Int): String =
        "${properties.forecastUrl}?latitude=${city.latitude}&longitude=${city.longitude}" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weather_code" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum,weather_code" +
            "&timezone=auto&forecast_days=$days"

    private fun getJson(url: String): JsonNode {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(properties.readTimeout)
            .header("Accept", "application/json")
            .GET()
            .build()

        log.debug("GET {}", url)
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString(UTF_8))
        } catch (e: IOException) {
            throw UpstreamException("Open-Meteo недоступен: ${e.message}", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw UpstreamException("Запрос к Open-Meteo прерван", e)
        }

        if (response.statusCode() >= 400) {
            log.warn("Open-Meteo ответил {}: {}", response.statusCode(), response.body().take(300))
            throw UpstreamException("Open-Meteo ответил ошибкой ${response.statusCode()}")
        }
        return objectMapper.readTree(response.body())
    }

    private fun JsonNode.toCity(): City = City(
        name = path("name").asString(),
        country = path("country").asString(),
        region = path("admin1").asString(""),
        latitude = path("latitude").asDouble(),
        longitude = path("longitude").asDouble(),
        timezone = path("timezone").asString(""),
    )

    private fun JsonNode.indices(): IntRange = 0 until size()

    private fun encode(value: String): String = URLEncoder.encode(value, UTF_8)
}

/** Внешний источник данных не ответил или ответил ошибкой. */
class UpstreamException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

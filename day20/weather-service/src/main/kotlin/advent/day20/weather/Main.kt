package advent.day20.weather

import advent.day20.common.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import java.time.Instant

fun main() {
    val service = WeatherService()
    embeddedServer(CIO, host = "127.0.0.1", port = env("WEATHER_PORT", "8211").toInt()) {
        routing {
            health(); swagger("Day20 · Погода", apiPaths(WEATHER_TOOLS))
            get("/api/cities") { call.api { call.json(service.cities(call.request.queryParameters["query"].orEmpty())) } }
            get("/api/weather/today") { call.api {
                val lat = call.request.queryParameters["latitude"]?.toDoubleOrNull()
                val lon = call.request.queryParameters["longitude"]?.toDoubleOrNull()
                checkInput(lat != null && lat.isFinite() && lat in -90.0..90.0 && lon != null && lon.isFinite() && lon in -180.0..180.0, "Нужны корректные latitude и longitude")
                call.json(service.today(lat!!, lon!!))
            } }
        }
    }.start(wait = true)
}

class WeatherService {
    private val http = RemoteHttp()
    suspend fun cities(query: String): JsonObject {
        checkInput(query.trim().length in 2..100, "Название города: от 2 до 100 символов")
        val raw = http.json(env("GEOCODING_URL", "https://geocoding-api.open-meteo.com/v1/search") + "?name=${encode(query.trim())}&count=5&language=ru&format=json")
        val cities = (raw["results"] as? JsonArray).orEmpty().map { item ->
            val c = item.jsonObject
            JsonObject(c.filterKeys { it in setOf("id", "name", "country", "admin1", "latitude", "longitude", "timezone", "population") })
        }
        return obj("query" to str(query), "cities" to JsonArray(cities), "source" to str("Open-Meteo / GeoNames"))
    }

    suspend fun today(latitude: Double, longitude: Double): JsonObject {
        val raw = http.json(env("FORECAST_URL", "https://api.open-meteo.com/v1/forecast") +
            "?latitude=$latitude&longitude=$longitude&timezone=auto&forecast_days=1&wind_speed_unit=ms" +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m" +
            "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max,precipitation_sum,sunrise,sunset")
        val daily = raw["daily"]?.jsonObject ?: throw ApiError(502, "Open-Meteo не вернул прогноз")
        val current = raw["current"]?.jsonObject ?: throw ApiError(502, "Open-Meteo не вернул текущую погоду")
        fun first(key: String) = (daily[key] as? JsonArray)?.firstOrNull() ?: JsonNull
        return buildJsonObject {
            put("date", first("time")); put("timezone", raw["timezone"] ?: JsonNull)
            put("latitude", latitude); put("longitude", longitude)
            put("current", JsonObject(current + ("description" to str(WeatherCodes.describe((current["weather_code"] as? JsonPrimitive)?.intOrNull)))))
            put("todayForecast", buildJsonObject {
                daily.keys.filter { it != "time" }.forEach { put(it, first(it)) }
                put("description", WeatherCodes.describe((first("weather_code") as? JsonPrimitive)?.intOrNull))
            })
            put("units", obj("temperature" to str("°C"), "wind" to str("m/s"), "precipitation" to str("mm"), "probability" to str("%")))
            put("fetchedAt", Instant.now().toString()); put("source", "https://open-meteo.com/")
            put("note", "Текущая погода — модельные данные; todayForecast — прогноз на весь сегодняшний день, включая ещё не наступившие часы.")
        }
    }
}

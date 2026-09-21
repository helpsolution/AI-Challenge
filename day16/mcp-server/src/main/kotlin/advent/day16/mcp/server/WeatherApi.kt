package advent.day16.mcp.server

import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration

class WeatherApi(private val baseUrl: String) {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    fun searchCities(query: String, limit: Int): List<City> =
        get<List<City>>("/api/cities?query=${encode(query)}&limit=$limit")

    fun currentWeather(city: String): CurrentWeather =
        get<CurrentWeather>("/api/weather/current?city=${encode(city)}")

    fun forecast(city: String, days: Int): Forecast =
        get<Forecast>("/api/weather/forecast?city=${encode(city)}&days=$days")

    private inline fun <reified T> get(path: String): T = json.decodeFromString<T>(getBody(path))

    private fun getBody(path: String): String {
        val request = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + path))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")
            .GET()
            .build()

        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString(UTF_8))
        } catch (e: IOException) {
            val reason = e.message?.takeIf { it.isNotBlank() } ?: "соединение не установлено"
            throw WeatherApiException("Сервис погоды недоступен по адресу $baseUrl: $reason")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw WeatherApiException("Запрос к сервису погоды прерван")
        }

        val body = response.body().orEmpty()
        if (response.statusCode() >= 400) {
            // Сервис объясняет отказ по-человечески — этот текст и увидит модель.
            val explained = runCatching { json.decodeFromString<ApiError>(body).message }.getOrNull()
            throw WeatherApiException(explained ?: "Сервис погоды ответил ошибкой ${response.statusCode()}")
        }
        return body
    }

    private fun encode(value: String): String = URLEncoder.encode(value, UTF_8)
}

class WeatherApiException(message: String) : RuntimeException(message)

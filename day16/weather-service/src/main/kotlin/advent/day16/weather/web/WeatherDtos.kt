package advent.day16.weather.web

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Город с координатами — результат геокодинга")
data class City(
    @field:Schema(description = "Название", example = "Москва")
    val name: String,
    @field:Schema(description = "Страна", example = "Россия")
    val country: String,
    @field:Schema(description = "Регион или штат", example = "Москва")
    val region: String,
    @field:Schema(description = "Широта", example = "55.75204")
    val latitude: Double,
    @field:Schema(description = "Долгота", example = "37.61781")
    val longitude: Double,
    @field:Schema(description = "Часовой пояс", example = "Europe/Moscow")
    val timezone: String,
)

@Schema(description = "Текущая погода в городе")
data class CurrentWeather(
    val city: City,
    @field:Schema(description = "Время наблюдения в часовом поясе города", example = "2026-09-21T19:15")
    val observedAt: String,
    @field:Schema(description = "Температура, °C", example = "18.7")
    val temperature: Double,
    @field:Schema(description = "Ощущаемая температура, °C", example = "18.2")
    val feelsLike: Double,
    @field:Schema(description = "Влажность, %", example = "59")
    val humidity: Int,
    @field:Schema(description = "Скорость ветра, км/ч", example = "4.2")
    val windSpeed: Double,
    @field:Schema(description = "Состояние неба", example = "пасмурно")
    val condition: String,
)

@Schema(description = "Прогноз по дням")
data class Forecast(
    val city: City,
    val days: List<ForecastDay>,
)

@Schema(description = "Один день прогноза")
data class ForecastDay(
    @field:Schema(description = "Дата", example = "2026-09-21")
    val date: String,
    @field:Schema(description = "Минимальная температура, °C", example = "13.7")
    val minTemperature: Double,
    @field:Schema(description = "Максимальная температура, °C", example = "21.0")
    val maxTemperature: Double,
    @field:Schema(description = "Сумма осадков, мм", example = "0.0")
    val precipitation: Double,
    @field:Schema(description = "Состояние неба", example = "пасмурно")
    val condition: String,
)

@Schema(description = "Ошибка запроса")
data class ErrorResponse(
    @field:Schema(description = "Человекочитаемое объяснение", example = "Город \"Кукуево123\" не найден")
    val message: String,
)

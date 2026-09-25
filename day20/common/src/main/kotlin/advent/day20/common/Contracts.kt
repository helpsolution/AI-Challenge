package advent.day20.common

import kotlinx.serialization.json.*

data class ToolSpec(val name: String, val description: String, val path: String, val properties: JsonObject, val required: List<String> = emptyList(), val post: Boolean = false)
val WEATHER_TOOLS = listOf(
    ToolSpec("search_city", "Найти город и его координаты. Если несколько подходящих городов, уточни у пользователя страну или регион.", "/api/cities", obj("query" to stringSchema("Название города")), listOf("query")),
    ToolSpec("today", "Погода сегодня по координатам: сейчас и прогноз на весь день. Часовой пояс определяется по координатам. Сначала получи координаты через search_city.", "/api/weather/today", obj(
        "latitude" to schema("number", "Широта", "minimum" to JsonPrimitive(-90), "maximum" to JsonPrimitive(90)),
        "longitude" to schema("number", "Долгота", "minimum" to JsonPrimitive(-180), "maximum" to JsonPrimitive(180))
    ), listOf("latitude", "longitude"))
)
val NEWS_TOOLS = listOf(ToolSpec("headlines", "До десяти публикаций Хабра: новые, популярные за сутки или смешанная подборка. Тема необязательна. Для сводки СЕГОДНЯ обязательно todayOnly=true и timezone города. Текст — только начало статьи, не полный текст. Пустой результат честно означает отсутствие подходящих публикаций в доступной RSS-выдаче.", "/api/news", obj(
    "topic" to stringSchema("Необязательная тема, например Kotlin или ИИ"),
    "mode" to stringSchema("Способ подбора; по умолчанию mixed", "new", "popular", "mixed"),
    "todayOnly" to schema("boolean", "Только опубликованные сегодня; по умолчанию false"),
    "timezone" to stringSchema("Часовой пояс для сегодняшнего дня, например Europe/Moscow"),
    "limit" to schema("integer", "Количество публикаций", "minimum" to JsonPrimitive(1), "maximum" to JsonPrimitive(10), "default" to JsonPrimitive(10))
)))
val IMAGE_TOOLS = listOf(ToolSpec("generate", "Создать ОДНО изображение по текстовому описанию и вернуть ссылку. Платный вызов OpenRouter; не повторять автоматически после ошибки. Для картинки сегодняшнего дня сначала получи погоду и новости, затем составь подробный художественный prompt с этими данными. Обычный запрос картинки не требует погоды и новостей.", "/api/images", obj(
    "prompt" to schema("string", "Полное описание желаемого изображения", "minLength" to JsonPrimitive(1), "maxLength" to JsonPrimitive(12000)),
    "aspectRatio" to stringSchema("Соотношение сторон, по умолчанию 16:9", "1:1", "16:9", "9:16", "4:3", "3:4")
), listOf("prompt"), post = true))
fun specs(kind: String) = when(kind) { "weather" -> WEATHER_TOOLS; "news" -> NEWS_TOOLS; "images" -> IMAGE_TOOLS; else -> error("Unknown service $kind") }
fun apiPaths(tools: List<ToolSpec>) = JsonObject(tools.map { it.path to obj((if (it.post) "post" else "get") to operation(it.description, it.properties, it.post, it.required)) }.toMap())

/** Validate at MCP boundary too: malformed model arguments must not silently become defaults. */
fun validateArguments(spec: ToolSpec, args: JsonObject) {
    checkInput(args.keys.all { it in spec.properties }, "Неизвестный аргумент инструмента")
    checkInput(spec.required.all { it in args && args[it] != JsonNull }, "Нужны аргументы: ${spec.required.joinToString()}")
    args.forEach { (key, value) ->
        val rule = spec.properties.getValue(key).jsonObject
        val p = value as? JsonPrimitive ?: throw ApiError(400, "$key: неверный тип")
        val valid = when (rule.text("type")) {
            "string" -> p.isString
            "boolean" -> !p.isString && p.booleanOrNull != null
            "integer" -> !p.isString && p.intOrNull != null
            "number" -> !p.isString && p.doubleOrNull?.isFinite() == true
            else -> false
        }
        checkInput(valid, "$key: неверный тип")
        (rule["enum"] as? JsonArray)?.let { checkInput(p in it, "$key: неподдерживаемое значение") }
        (rule["minimum"] as? JsonPrimitive)?.doubleOrNull?.let { checkInput(p.double >= it, "$key: меньше минимума") }
        (rule["maximum"] as? JsonPrimitive)?.doubleOrNull?.let { checkInput(p.double <= it, "$key: больше максимума") }
        (rule["minLength"] as? JsonPrimitive)?.intOrNull?.let { checkInput(p.content.trim().length >= it, "$key: слишком короткое значение") }
        (rule["maxLength"] as? JsonPrimitive)?.intOrNull?.let { checkInput(p.content.length <= it, "$key: слишком длинное значение") }
    }
}

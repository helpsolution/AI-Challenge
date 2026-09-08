package advent.day7.agent

/**
 * Настройки агента: с чем он работает. Приходят из `application.yml` и на ходу не меняются —
 * в этот день интересна память, а не перенастройка.
 *
 * Проверяет их сам агент, а не конфиг: сущность обязана защищать себя сама, откуда бы
 * настройки ни пришли.
 */
data class AgentSettings(
    val name: String,
    /** Системная инструкция. Пустая строка — агент без характера, голая модель. */
    val persona: String,
    val model: String,
    val temperature: Double,
    val maxTokens: Int,
) {
    init {
        require(name.isNotBlank()) { "Имя агента не может быть пустым" }
        require(model.isNotBlank()) { "Модель не задана" }
        require(temperature in 0.0..2.0) { "Температура должна быть от 0 до 2" }
        require(maxTokens in 16..8192) { "Потолок ответа должен быть от 16 до 8192 токенов" }
    }
}

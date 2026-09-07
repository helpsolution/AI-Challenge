package advent.day6.agent

/** Облик агента в интерфейсе. Персону не меняет — только рисунок и его повадки. */
enum class Avatar { CAT, DOG }

/**
 * Настройки агента — то, что пользователь может поменять на лету, не перезапуская приложение.
 * Валидация живёт здесь, а не в DTO контроллера: агент обязан защищать себя сам,
 * откуда бы настройки ни пришли — из конфига, из API или из теста.
 */
data class AgentSettings(
    val name: String,
    val avatar: Avatar,
    /** Системная инструкция. Пустая строка — агент без характера, голая модель. */
    val persona: String,
    val model: String,
    val temperature: Double,
    val maxTokens: Int,
) {
    fun validate(allowedModels: Set<String>) {
        require(name.isNotBlank()) { "Имя агента не может быть пустым" }
        require(name.length <= MAX_NAME_LENGTH) { "Имя агента длиннее $MAX_NAME_LENGTH символов" }
        require(persona.length <= MAX_PERSONA_LENGTH) { "Персона длиннее $MAX_PERSONA_LENGTH символов" }
        require(model in allowedModels) {
            "Неизвестная модель '$model'. Доступны: ${allowedModels.sorted().joinToString(", ")}"
        }
        require(temperature in 0.0..MAX_TEMPERATURE) { "Температура должна быть от 0 до $MAX_TEMPERATURE" }
        require(maxTokens in MIN_MAX_TOKENS..MAX_MAX_TOKENS) {
            "Потолок ответа должен быть от $MIN_MAX_TOKENS до $MAX_MAX_TOKENS токенов"
        }
    }

    /** Человекочитаемый список отличий — для журнала агента. */
    fun describeChangesFrom(old: AgentSettings): List<String> = buildList {
        if (name != old.name) add("имя: ${old.name} → $name")
        if (avatar != old.avatar) add("облик: ${old.avatar.title()} → ${avatar.title()}")
        if (persona != old.persona) add("персона переписана")
        if (model != old.model) add("модель: ${old.model} → $model")
        if (temperature != old.temperature) add("температура: ${old.temperature} → $temperature")
        if (maxTokens != old.maxTokens) add("потолок ответа: ${old.maxTokens} → $maxTokens токенов")
    }

    companion object {
        const val MAX_NAME_LENGTH = 40
        const val MAX_PERSONA_LENGTH = 4000
        const val MAX_TEMPERATURE = 2.0
        const val MIN_MAX_TOKENS = 16
        const val MAX_MAX_TOKENS = 8192
    }
}

fun Avatar.title(): String = when (this) {
    Avatar.CAT -> "кот"
    Avatar.DOG -> "пёс"
}

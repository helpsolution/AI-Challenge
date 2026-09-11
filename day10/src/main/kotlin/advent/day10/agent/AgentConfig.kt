package advent.day10.agent

import advent.day10.chat.StrategyId

/**
 * Настройки агента: с чем он работает и с какими стратегиями по умолчанию.
 *
 * Проверяет их сам агент, а не Spring: сущность обязана защищать себя сама, откуда бы
 * настройки ни пришли — из `application.yml`, из теста или из прогона сценария.
 *
 * [defaultStrategy] и [defaultWindowSize] — именно значения по умолчанию. Работающая
 * сессия берёт их не отсюда, а из своей записи в базе: настройки менялись бы задним
 * числом, и старые прогоны перестали бы значить то, что значили.
 */
data class AgentConfig(
    val name: String,
    /** Системная инструкция. Пустая строка — агент без характера, голая модель. */
    val persona: String,
    val model: String,
    val temperature: Double,
    val maxTokens: Int,
    /**
     * Лимит контекста модели в токенах. У `deepseek-chat` это 1 000 000.
     *
     * Провайдер размер контекста в ответе не сообщает, взять его больше негде. Здесь он
     * нужен не для защиты — упереться в миллион руками невозможно, — а чтобы показывать
     * в интерфейсе, какую долю доступного контекста стратегия на самом деле использует.
     */
    val contextLimit: Int,
    val defaultStrategy: StrategyId,
    val defaultWindowSize: Int,
) {
    init {
        require(name.isNotBlank()) { "Имя агента не может быть пустым" }
        require(model.isNotBlank()) { "Модель не задана" }
        require(temperature in 0.0..2.0) { "Температура должна быть от 0 до 2" }
        require(maxTokens in 16..8192) { "Потолок ответа должен быть от 16 до 8192 токенов" }
        require(contextLimit > maxTokens) {
            "Лимит контекста ($contextLimit) должен быть больше потолка ответа ($maxTokens): " +
                "лимит считается вместе с ответом, иначе на промпт не остаётся места"
        }
        require(defaultWindowSize >= MIN_WINDOW) { "Окно меньше $MIN_WINDOW сообщений бессмысленно" }
    }

    /**
     * Сколько контекста остаётся под промпт: лимит считается вместе с ответом.
     *
     * Это не наша догадка, а то, что написано в ошибке провайдера при переполнении:
     * «you requested about 21250 tokens (21190 of text input, 60 in the output)».
     */
    val contextForPrompt: Int get() = contextLimit - maxTokens

    companion object {
        /**
         * Окно должно вмещать хотя бы одну пару «вопрос-ответ» и текущий вопрос.
         * При меньшем модель на каждом ходу видит обрывок и начинает переспрашивать —
         * это уже не стратегия, а поломка.
         */
        const val MIN_WINDOW = 2
    }
}

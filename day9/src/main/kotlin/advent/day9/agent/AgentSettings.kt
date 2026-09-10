package advent.day9.agent

import advent.day9.context.ContextPolicy

/**
 * Настройки агента: с чем он работает. Приходят из `application.yml` и на ходу не меняются —
 * переключателей в интерфейсе нет намеренно, иначе половина дня ушла бы на них, а не на
 * сравнение режимов.
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
    /**
     * Лимит контекста выбранной модели в токенах.
     *
     * Задаётся в конфиге рядом с моделью и меняется вместе с ней: у `gpt-3.5-turbo-0613`
     * это 4095, у моделей DeepSeek — 1 000 000. Провайдер размер контекста в ответе
     * не сообщает, поэтому взять его больше негде.
     */
    val contextLimit: Int,
    /** Как собирается контекст: дословно или с конспектом. Главный переключатель дня. */
    val context: ContextPolicy,
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
    }

    /**
     * Сколько контекста остаётся под промпт.
     *
     * Лимит модели считается вместе с ответом — это не догадка, а то, что написано
     * в ошибке провайдера: «you requested about 21250 tokens (21190 of text input,
     * 60 in the output)». Значит потолок ответа съедает часть контекста ещё до того,
     * как модель что-то сказала.
     */
    val contextForPrompt: Int get() = contextLimit - maxTokens

    /** Какой моделью сворачивать историю: своей из конфига или той же, что отвечает. */
    val summaryModel: String get() = context.summaryModel?.takeIf { it.isNotBlank() } ?: model
}

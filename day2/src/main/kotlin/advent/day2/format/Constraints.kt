package advent.day2.format

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size

/** Форма, в которую загоняем ответ модели. */
enum class ResponseFormat {
    /** Без требований к форме — модель отвечает как хочет. */
    FREE,
    JSON,
    BULLETS,
    TABLE,
    SENTENCE,
}

/**
 * Набор ограничений: описание формата, потолок длины и условие завершения.
 * Каждое поле включается отдельно — выключенное не попадает ни в промпт, ни в параметры API.
 */
data class Constraints(
    val format: ResponseFormat = ResponseFormat.FREE,

    /** Скелет ожидаемого JSON — по нему же потом проверяется структура ответа. */
    @field:Size(max = 4_000, message = "Схема длиннее 4000 символов")
    val jsonSchema: String? = null,

    /** Просить провайдера включить JSON-режим (`response_format: json_object`). */
    val jsonMode: Boolean = false,

    @field:Min(value = 1, message = "Лимит слов: минимум 1")
    @field:Max(value = 4_000, message = "Лимит слов: максимум 4000")
    val maxWords: Int? = null,

    @field:Min(value = 1, message = "Лимит пунктов: минимум 1")
    @field:Max(value = 100, message = "Лимит пунктов: максимум 100")
    val maxItems: Int? = null,

    @field:Min(value = 1, message = "max_tokens: минимум 1")
    @field:Max(value = 8_192, message = "max_tokens: максимум 8192")
    val maxTokens: Int? = null,

    /** Стоп-последовательность для API: генерация обрывается на ней. */
    @field:Size(max = 32, message = "Стоп-последовательность длиннее 32 символов")
    val stopSequence: String? = null,

    /** Явная инструкция завершить ответ маркером — работает и без стоп-последовательности. */
    val endMarkerInstruction: Boolean = false,

    /** Маркер завершения, который просим поставить в конце. */
    @field:Size(max = 32, message = "Маркер длиннее 32 символов")
    val endMarker: String? = null,
) {
    /** Ограничения, которые ничего не ограничивают, — эталон «без правил». */
    fun isEmpty(): Boolean =
        format == ResponseFormat.FREE && !jsonMode && maxWords == null &&
            maxItems == null && maxTokens == null && stopSequence.isNullOrBlank() && !endMarkerInstruction

    companion object {
        val NONE = Constraints()
    }
}

package advent.translator.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "translator")
data class TranslatorProperties(
    val languages: List<TargetLanguage> = emptyList(),
    val maxInputChars: Int = 10_000,
) {
    init {
        require(maxInputChars > 0) { "translator.max-input-chars должен быть положительным" }
        require(languages.isNotEmpty()) { "Укажите хотя бы один язык в translator.languages" }
        require(languages.map { it.code }.distinct().size == languages.size) {
            "Коды в translator.languages не должны повторяться"
        }
    }
}

data class TargetLanguage(
    val code: String,
    val name: String,
) {
    init {
        require(code.matches(Regex("[a-z]{2,3}(-[A-Z]{2})?"))) { "Некорректный код языка: $code" }
        require(name.isNotBlank()) { "Название языка не должно быть пустым" }
    }
}

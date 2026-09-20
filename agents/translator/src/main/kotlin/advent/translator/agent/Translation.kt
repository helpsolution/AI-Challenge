package advent.translator.agent

data class Translation(
    val code: String,
    val language: String,
    val text: String,
)

data class TranslationResult(val translations: List<Translation>)

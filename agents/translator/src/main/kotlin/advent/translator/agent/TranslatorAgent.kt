package advent.translator.agent

import advent.translator.config.TargetLanguage
import advent.translator.config.TranslatorProperties
import advent.translator.llm.LlmClient
import advent.translator.llm.LlmException
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/** Один запрос = одно решение о переводе всех настроенных языков и один вызов LLM. */
@Service
class TranslatorAgent(
    private val properties: TranslatorProperties,
    private val promptBuilder: PromptBuilder,
    private val llmClient: LlmClient,
    private val objectMapper: ObjectMapper,
) {
    fun languages(): List<TargetLanguage> = properties.languages

    fun translate(text: String): TranslationResult {
        require(text.isNotBlank()) { "Поле text не должно быть пустым" }
        require(text.length <= properties.maxInputChars) {
            "Поле text не должно превышать ${properties.maxInputChars} символов"
        }

        val messages = promptBuilder.build(text, properties.languages)
        val answer = llmClient.complete(messages)
        val parsed = try {
            objectMapper.readValue(answer, TranslationPayload::class.java)
        } catch (e: Exception) {
            throw LlmException("DeepSeek вернул некорректный JSON с переводами", cause = e)
        }

        val expected = properties.languages.map { it.code }.toSet()
        if (parsed.translations.keys != expected || parsed.translations.values.any { it.isBlank() }) {
            throw LlmException("DeepSeek вернул неполный или некорректный список переводов")
        }

        return TranslationResult(
            properties.languages.map { language ->
                Translation(language.code, language.name, parsed.translations.getValue(language.code))
            },
        )
    }

    private data class TranslationPayload(val translations: Map<String, String> = emptyMap())
}

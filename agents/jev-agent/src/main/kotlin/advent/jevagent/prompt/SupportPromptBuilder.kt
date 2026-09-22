package advent.jevagent.prompt

import advent.jevagent.config.JevProperties
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/** Forms the complete JSON body that the agent asks Jev to evaluate. */
@Component
class SupportPromptBuilder(
    private val properties: JevProperties,
    private val mapper: ObjectMapper,
) {
    fun build(message: String): String = mapper.writeValueAsString(mapOf(
        "state" to message,
        "model" to properties.model,
        "questions" to questions,
    ))

    private val questions: Map<String, Any> = linkedMapOf(
        "topic" to mapOf(
            "type" to "choice",
            "instructions" to "Какова основная тема этого обращения в поддержку? Оценивайте только то, что прямо следует из текста.",
            "criteria" to linkedMapOf(
                "product_bug" to "Функция продукта не работает, выдаёт ошибку или ведёт себя неправильно.",
                "billing" to "Платежи, счета, списания или оплата подписки.",
                "how_to" to "Вопрос об использовании существующей функции или поиске информации.",
                "feature_request" to "Просьба добавить новую функцию или изменить поведение продукта.",
                "other" to "Ни одна тема явно не подходит или сообщение слишком расплывчато.",
            ),
        ),
        "reported_impact" to mapOf(
            "type" to "score",
            "instructions" to "Насколько велик масштаб проблемы, заявленный в сообщении? Оценивайте слова автора, а не фактическую серьёзность инцидента. Если масштаб не указан, выбирайте нижний уровень.",
            "criteria" to listOf(
                "Влияние на работу не указано или масштаб неизвестен.",
                "Одному пользователю неудобно или есть обходной путь.",
                "Хотя бы один пользователь не может выполнить важную задачу или затронуты несколько пользователей.",
                "Критичный сервис недоступен многим пользователям либо есть риск для данных или денег.",
            ),
        ),
        "engineering_needed" to mapOf(
            "type" to "noul",
            "instructions" to "Судя только по этому тексту, потребуется ли для решения проблемы участие разработчика: исследование технической причины или изменение кода продукта? Объяснение поддержки, действие с учётной записью или готовая инструкция сами по себе не считаются участием разработчика.",
        ),
    )
}

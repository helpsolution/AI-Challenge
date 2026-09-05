package advent.day5.catalog

/**
 * Карточка модели: постоянная часть из конфигурации плюс живые данные провайдера.
 *
 * Прайс и размер контекста не захардкожены намеренно. Цены на модели меняются
 * несколько раз в год, а день 5 меряет в том числе деньги — цифра из конфига,
 * написанная однажды, врала бы уже через месяц.
 */
data class ModelCard(
    val id: String,
    val tier: ModelTier,
    val title: String,
    val scale: String,
    val default: Boolean,
    /** Долларов за миллион входных токенов. null — провайдер недоступен, цену не знаем. */
    val promptPricePerMillion: Double? = null,
    /** Долларов за миллион выходных токенов. */
    val completionPricePerMillion: Double? = null,
    val contextLength: Int? = null,
    /** Карточка модели у провайдера — та самая ссылка, которую просит задание. */
    val openRouterUrl: String,
    /** Веса на HuggingFace. Есть только у открытых моделей. */
    val huggingFaceUrl: String? = null,
) {
    /** Бесплатные модели тоже бывают: у них обе цены нулевые. */
    val free: Boolean
        get() = promptPricePerMillion == 0.0 && completionPricePerMillion == 0.0
}

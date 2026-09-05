package advent.day5.catalog

import advent.day5.config.OpenRouterProperties
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Каталог моделей: список из конфигурации, обогащённый прайсом и ссылками от провайдера.
 *
 * Публичная ручка `/api/v1/models` ходит без ключа и отдаёт цену за токен, размер
 * контекста и идентификатор весов на HuggingFace. Тянем её лениво, при первом обращении,
 * и держим в кэше [TTL]: при старте приложения сеть может быть не готова, а падать
 * из-за справочника цен незачем.
 *
 * Если провайдер недоступен, каталог всё равно отдаётся — просто без цен. Приложение
 * при этом работает: реальную стоимость запроса присылает сам ответ модели,
 * а прайс нужен только чтобы показать его в карточке.
 */
@Service
class ModelCatalog(
    private val catalogRestClient: RestClient,
    private val properties: OpenRouterProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cache = AtomicReference<Snapshot?>(null)

    fun cards(): List<ModelCard> {
        val live = pricing()
        return properties.catalog.map { entry ->
            val remote = live[entry.id]
            ModelCard(
                id = entry.id,
                tier = entry.tier,
                title = entry.title.ifBlank { entry.id },
                scale = entry.scale,
                default = entry.default,
                promptPricePerMillion = remote?.pricing?.prompt?.perMillion(),
                completionPricePerMillion = remote?.pricing?.completion?.perMillion(),
                contextLength = remote?.contextLength,
                openRouterUrl = "https://openrouter.ai/${entry.id}",
                huggingFaceUrl = remote?.huggingFaceId
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "https://huggingface.co/$it" },
            )
        }
    }

    fun card(id: String): ModelCard? = cards().firstOrNull { it.id == id }

    /** Модель уровня по умолчанию: первая помеченная `default`, иначе первая в уровне. */
    fun defaultOf(tier: ModelTier): ModelCard? {
        val ofTier = cards().filter { it.tier == tier }
        return ofTier.firstOrNull { it.default } ?: ofTier.firstOrNull()
    }

    private fun pricing(): Map<String, RemoteModel> {
        cache.get()?.takeIf { Duration.between(it.fetchedAt, Instant.now()) < TTL }?.let { return it.models }

        val fetched = try {
            catalogRestClient.get()
                .uri(properties.modelsUrl)
                .retrieve()
                .body(RemoteModels::class.java)
                ?.data
                ?.associateBy { it.id }
                .orEmpty()
        } catch (e: Exception) {
            // Справочник цен — украшение карточки, а не условие работы.
            // Отдаём то, что есть в кэше, и не мешаем пользователю сравнивать модели.
            log.warn("Не удалось получить прайс провайдера: {}", e.message)
            return cache.get()?.models.orEmpty()
        }

        cache.set(Snapshot(fetched, Instant.now()))
        log.info("Прайс провайдера обновлён: {} моделей", fetched.size)
        return fetched
    }

    private data class Snapshot(val models: Map<String, RemoteModel>, val fetchedAt: Instant)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class RemoteModels(val data: List<RemoteModel> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class RemoteModel(
        val id: String = "",
        @JsonProperty("context_length") val contextLength: Int? = null,
        @JsonProperty("hugging_face_id") val huggingFaceId: String? = null,
        val pricing: RemotePricing? = null,
    )

    /** Провайдер отдаёт цену строкой и за один токен: «0.00000027». */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class RemotePricing(
        val prompt: String? = null,
        val completion: String? = null,
    )

    private companion object {
        val TTL: Duration = Duration.ofHours(6)
        val MILLION: BigDecimal = BigDecimal(1_000_000)

        /**
         * Цена за миллион токенов. Считается на BigDecimal, а не на Double: «0.00000005»,
         * умноженное на миллион в двоичной арифметике, даёт 0.049999999999999996 —
         * и интерфейс показывает мусор вместо пяти сотых.
         */
        fun String.perMillion(): Double? = runCatching {
            BigDecimal(this).multiply(MILLION).stripTrailingZeros().toDouble()
        }.getOrNull()
    }
}

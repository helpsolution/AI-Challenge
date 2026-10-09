package advent.llmservice.ollama

import advent.llmservice.LlmProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import kotlin.time.TimeSource

/** Единственная модель сервиса с фиксированным окном контекста. */
@Component
class LocalModel(private val props: LlmProperties, private val ollama: OllamaClient) {
    private val log = LoggerFactory.getLogger(javaClass)

    val name get() = props.model

    fun generate(messages: List<OllamaMessage>, maxTokens: Int, temperature: Double?): OllamaClient.Generation =
        ollama.chat(OllamaChatRequest(props.model, messages, options(maxTokens, temperature)))

    // num_ctx и флаги окна у всех запросов одинаковые: другое значение — и Ollama перезагружает модель (2 с на VPS).
    private fun options(maxTokens: Int, temperature: Double?): Map<String, Any> = buildMap {
        put("num_ctx", props.contextTokens)
        put("num_predict", maxTokens)
        temperature?.let { put("temperature", it) }
    }

    /**
     * Загружает модель при старте тем же /api/chat, что и обычные запросы. Пустой промпт в /api/generate
     * не подходит: первый чат с `shift: false` всё равно перезагружает модель.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun warmUp() {
        Thread.startVirtualThread {
            val started = TimeSource.Monotonic.markNow()
            try {
                generate(listOf(OllamaMessage("user", "Привет")), 1, null).use { it.collect {} }
                log.info("Модель {} в памяти, прогрев {} мс", props.model, started.elapsedNow().inWholeMilliseconds)
            } catch (e: RuntimeException) {
                log.warn("Модель {} не прогрета: {}", props.model, e.message)
            }
        }
    }
}

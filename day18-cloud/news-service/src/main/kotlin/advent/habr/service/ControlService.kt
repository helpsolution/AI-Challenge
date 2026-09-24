package advent.habr.service

import advent.habr.config.NewsProperties
import advent.habr.storage.ArticleRepository
import advent.habr.storage.StateRepository
import advent.habr.web.LastRun
import advent.habr.web.Status
import org.springframework.stereotype.Service

/** Единственная настройка, которую можно менять на ходу, — включён ли сбор. Остальное живёт в application.yml. */
@Service
class ControlService(
    private val properties: NewsProperties,
    private val state: StateRepository,
    private val articles: ArticleRepository,
    private val collector: Collector,
) {
    fun status(): Status {
        val current = state.get()
        return Status(
            enabled = current.enabled,
            source = SOURCE,
            everyMinutes = properties.every.toMinutes().toInt(),
            nextRunAt = collector.nextRunAt(current)?.toString(),
            lastRun = current.lastRunAt?.let {
                LastRun(
                    at = it.toString(),
                    found = current.lastFound ?: 0,
                    added = current.lastAdded ?: 0,
                    error = current.lastError,
                )
            },
            articles = articles.count(),
            retentionDays = properties.retention.toDays().toInt(),
        )
    }

    /**
     * Включение сразу делает сбор, не дожидаясь тика: после «включи» сводку можно просить сразу.
     * Повторное включение ничего не собирает — запрос идемпотентен.
     */
    fun setEnabled(enabled: Boolean): Status {
        val wasEnabled = state.get().enabled
        state.setEnabled(enabled)
        if (enabled && !wasEnabled) collector.collect()
        return status()
    }

    private companion object {
        const val SOURCE = "Хабр"
    }
}

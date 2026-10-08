package advent.lab.experiment

import advent.lab.task.TicketTask
import org.springframework.stereotype.Component

/** Готовая конфигурация без модели: модель выбирается отдельно, чтобы один пресет прогонять на разных квантованиях. */
data class Preset(
    val id: String,
    val label: String,
    val description: String,
    val system: String,
    val fewShot: Boolean,
    val schema: Boolean,
    val options: Options,
)

@Component
class Presets(task: TicketTask) {
    val all = listOf(
        Preset(
            id = "baseline", label = "Из коробки",
            description = "Первая попытка: короткий промпт со списком полей, без примеров и схемы. Параметры не заданы — " +
                "Ollama берёт их из модели.",
            system = task.naivePrompt, fewShot = false, schema = false, options = Options(),
        ),
        Preset(
            id = "optimized", label = "Настроенный",
            description = "Определения категорий и правила срочности, 4 примера, JSON-схема в format, temperature 0, " +
                "окно 2048 вместо 32 768 и потолок ответа 128 токенов.",
            system = task.optimizedPrompt, fewShot = true, schema = true,
            options = Options(temperature = 0.0, numCtx = 2048, numPredict = 128),
        ),
        Preset(
            id = "built", label = "Собранная",
            description = "Для модели, собранной кнопкой «Собрать модель»: промпт, примеры и параметры уже внутри неё. " +
                "Отправляется только обращение и JSON-схема — её в Modelfile не записать.",
            system = "", fewShot = false, schema = true, options = Options(),
        ),
    )
}

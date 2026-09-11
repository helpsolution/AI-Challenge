package advent.day10.context

import advent.day10.chat.StrategyId

/**
 * Переключатель стратегий: по идентификатору из сессии — реализация.
 *
 * [StrategyId.BRANCHING] намеренно указывает на то же скользящее окно. Ветвление —
 * не способ собрать промпт, а способ выбрать, какая история считается текущей; в базе
 * это форк сессии, и к сборке запроса отношения не имеет. В переключателе интерфейса
 * пункт отдельный, потому что этого требует задание, а в коде отдельной сборки под него
 * нет, потому что её и не существует.
 *
 * [StrategyId.FACTS] пока не зарегистрирована: сегодня реализована одна стратегия,
 * остальные добавляются в эту карту и больше нигде.
 */
class StrategyRegistry(strategies: List<ContextStrategy>) {

    private val byId: Map<StrategyId, ContextStrategy> = strategies.associateBy { it.id }

    val available: Set<StrategyId> get() = byId.keys

    /**
     * Требование, а не поиск: сессия без стратегии работать не может, и молча подставить
     * «какую-нибудь» — худшее, что здесь можно сделать. Прогон сравнения тогда показал бы
     * числа не той стратегии, которая написана в заголовке.
     */
    fun require(id: StrategyId): ContextStrategy = byId[id]
        ?: throw IllegalArgumentException(
            "Стратегия $id ещё не реализована. Доступны: ${available.joinToString()}",
        )
}

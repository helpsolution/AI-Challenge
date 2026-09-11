package advent.day10.store

import advent.day10.chat.Exchange
import advent.day10.chat.Fact
import advent.day10.chat.Message
import advent.day10.chat.Role
import advent.day10.chat.Session
import advent.day10.chat.StrategyId
import advent.day10.chat.Turn
import advent.day10.chat.Upkeep
import advent.day10.llm.CostSource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant

/**
 * Реализация на SQLite. Единственное место в приложении, которое знает SQL.
 *
 * Часы приходят снаружи, а не берутся из `Instant.now()` по месту: время проставляется
 * здесь, потому что здесь же присваиваются идентификаторы, и держать эти два решения
 * в одном месте проще, чем сверять их потом.
 */
@Repository
class SqliteChatStore(
    private val jdbc: JdbcClient,
    private val clock: Clock,
) : ChatStore, FactStore {

    override fun sessions(): List<Session> =
        jdbc.sql("SELECT * FROM session ORDER BY id DESC").query { rs, _ -> rs.toSession() }.list()

    override fun session(id: Long): Session? =
        jdbc.sql("SELECT * FROM session WHERE id = ?").param(id)
            .query { rs, _ -> rs.toSession() }.optional().orElse(null)

    override fun createSession(title: String, strategy: StrategyId, windowSize: Int): Session {
        val now = clock.instant()
        val id = insertSession(title, strategy, windowSize, now, parentId = null, forkedAfter = null)
        return Session(id, title, strategy, windowSize, now)
    }

    override fun branches(parentId: Long): List<Session> =
        jdbc.sql("SELECT * FROM session WHERE parent_id = ? ORDER BY id").param(parentId)
            .query { rs, _ -> rs.toSession() }.list()

    /**
     * Форк — одна вставка сессии и одна вставка сообщений через `INSERT ... SELECT`.
     *
     * Копирование целиком остаётся в базе: ни одна строка переписки не поднимается
     * в приложение и не едет обратно. Поэтому стоимость ветвления не зависит от длины
     * диалога и от того, сколько в нём текста.
     */
    @Transactional
    override fun fork(parentId: Long, afterMessageId: Long, title: String): Session {
        val parent = requireSession(parentId)
        val belongs = jdbc.sql("SELECT count(*) FROM message WHERE id = ? AND session_id = ?")
            .params(afterMessageId, parentId).query(Int::class.java).single()
        require(belongs == 1) { "Сообщение $afterMessageId не принадлежит сессии $parentId" }

        val now = clock.instant()
        // Стратегия и размер окна наследуются: ветка нужна, чтобы сравнить два продолжения
        // одного разговора, а не два разных режима. Смена стратегии — это новая сессия.
        val id = insertSession(title, parent.strategy, parent.windowSize, now, parentId, forkedAfter = 0)

        val copied = jdbc.sql(
            """
            INSERT INTO message (session_id, role, content, created_at)
            SELECT ?, role, content, created_at FROM message
            WHERE session_id = ? AND id <= ? ORDER BY id
            """.trimIndent(),
        ).params(id, parentId, afterMessageId).update()

        // Досье переезжает вместе с перепиской: ветка обязана знать то же, что знал
        // родитель в точке отделения.
        jdbc.sql(
            """
            INSERT INTO fact (session_id, key, value, turn_number, updated_at)
            SELECT ?, key, value, turn_number, updated_at FROM fact WHERE session_id = ?
            """.trimIndent(),
        ).params(id, parentId).update()

        jdbc.sql("UPDATE session SET forked_after = ? WHERE id = ?").params(copied, id).update()
        return Session(id, title, parent.strategy, parent.windowSize, now, parentId, copied)
    }

    @Transactional
    override fun deleteSession(id: Long) {
        jdbc.sql("DELETE FROM upkeep WHERE session_id = ?").param(id).update()
        jdbc.sql("DELETE FROM fact WHERE session_id = ?").param(id).update()
        jdbc.sql("DELETE FROM turn WHERE session_id = ?").param(id).update()
        jdbc.sql("DELETE FROM message WHERE session_id = ?").param(id).update()
        // Ветки не удаляем каскадом: у них своя переписка, и терять её вместе с родителем
        // пользователь не просил. Ссылка повисает — это видно в интерфейсе как «ветка от
        // удалённого диалога» и лучше, чем молча снесённая работа.
        jdbc.sql("DELETE FROM session WHERE id = ?").param(id).update()
    }

    override fun history(sessionId: Long): List<Message> =
        jdbc.sql("SELECT * FROM message WHERE session_id = ? ORDER BY id").param(sessionId)
            .query { rs, _ -> rs.toMessage() }.list()

    override fun countMessages(sessionId: Long): Int =
        jdbc.sql("SELECT count(*) FROM message WHERE session_id = ?").param(sessionId)
            .query(Int::class.java).single()

    /**
     * Ходы вместе с расходом на обслуживание памяти.
     *
     * `LEFT JOIN`, а не два запроса: у скользящего окна строк обслуживания нет вовсе,
     * и отдельный запрос за ними каждый раз возвращал бы пустоту.
     */
    override fun turns(sessionId: Long): List<Turn> =
        jdbc.sql(
            """
            SELECT t.*,
                   u.note              AS u_note,
                   u.latency_ms        AS u_latency_ms,
                   u.prompt_tokens     AS u_prompt_tokens,
                   u.cached_tokens     AS u_cached_tokens,
                   u.completion_tokens AS u_completion_tokens,
                   u.total_tokens      AS u_total_tokens,
                   u.cost_usd          AS u_cost_usd,
                   u.cost_source       AS u_cost_source,
                   u.error             AS u_error
            FROM turn t
            LEFT JOIN upkeep u ON u.session_id = t.session_id AND u.turn_number = t.number
            WHERE t.session_id = ? ORDER BY t.number
            """.trimIndent(),
        ).param(sessionId).query { rs, _ -> rs.toTurn() }.list()

    override fun saveUpkeep(sessionId: Long, turnNumber: Int, upkeep: Upkeep) {
        jdbc.sql(
            """
            INSERT INTO upkeep (session_id, turn_number, note, latency_ms, prompt_tokens,
                                cached_tokens, completion_tokens, total_tokens, cost_usd,
                                cost_source, error)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        )
            .params(
                listOf(
                    sessionId, turnNumber, upkeep.note, upkeep.latencyMs, upkeep.promptTokens,
                    upkeep.cachedPromptTokens, upkeep.completionTokens, upkeep.totalTokens,
                    upkeep.costUsd, upkeep.costSource?.name, upkeep.error,
                ),
            )
            .update()
    }

    // --- досье стратегии фактов ---

    override fun facts(sessionId: Long): List<Fact> =
        jdbc.sql("SELECT * FROM fact WHERE session_id = ? ORDER BY rowid").param(sessionId)
            .query { rs, _ ->
                Fact(
                    key = rs.getString("key"),
                    value = rs.getString("value"),
                    turnNumber = rs.getInt("turn_number"),
                    at = Instant.parse(rs.getString("updated_at")),
                )
            }
            .list()

    @Transactional
    override fun applyFacts(
        sessionId: Long,
        set: Map<String, String>,
        remove: Collection<String>,
        turnNumber: Int,
        limit: Int,
    ): Int {
        val at = clock.instant().toString()
        remove.forEach {
            jdbc.sql("DELETE FROM fact WHERE session_id = ? AND key = ?").params(sessionId, it).update()
        }
        set.forEach { (key, value) ->
            // ON CONFLICT DO UPDATE, а не «удалить и вставить»: досье — словарь,
            // и повторно названный дедлайн обязан заменить прежний, а не лечь рядом.
            jdbc.sql(
                """
                INSERT INTO fact (session_id, key, value, turn_number, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (session_id, key)
                DO UPDATE SET value = excluded.value, turn_number = excluded.turn_number,
                              updated_at = excluded.updated_at
                """.trimIndent(),
            ).params(sessionId, key, value, turnNumber, at).update()
        }

        // Потолок: выбывают те, которых дольше всех не касались. Правило грубое,
        // но предсказуемое — в отличие от «попросим модель сократить».
        return jdbc.sql(
            """
            DELETE FROM fact WHERE session_id = ? AND key NOT IN (
                SELECT key FROM fact WHERE session_id = ? ORDER BY turn_number DESC, rowid DESC LIMIT ?
            )
            """.trimIndent(),
        ).params(sessionId, sessionId, limit).update()
    }

    @Transactional
    override fun saveTurn(sessionId: Long, question: String, answer: String, turn: Turn): Exchange {
        val asked = insertMessage(sessionId, Role.USER, question)
        val answered = insertMessage(sessionId, Role.ASSISTANT, answer)
        insert(turn)
        return Exchange(asked, answered, turn)
    }

    override fun saveFailedTurn(turn: Turn) {
        insert(turn)
    }

    private fun insertSession(
        title: String,
        strategy: StrategyId,
        windowSize: Int,
        at: Instant,
        parentId: Long?,
        forkedAfter: Int?,
    ): Long {
        val keys = GeneratedKeyHolder()
        jdbc.sql(
            """
            INSERT INTO session (title, strategy, window_size, created_at, parent_id, forked_after)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        )
            // params(List), а не vararg: у корневой сессии parent_id равен null,
            // а vararg-перегрузка null не принимает.
            .params(listOf(title, strategy.name, windowSize, at.toString(), parentId, forkedAfter))
            .update(keys)
        return keys.key?.toLong() ?: error("SQLite не вернул идентификатор новой сессии")
    }

    private fun insertMessage(sessionId: Long, role: Role, content: String): Message {
        val at = clock.instant()
        val keys = GeneratedKeyHolder()
        jdbc.sql("INSERT INTO message (session_id, role, content, created_at) VALUES (?, ?, ?, ?)")
            .params(sessionId, role.name, content, at.toString())
            .update(keys)
        val id = keys.key?.toLong() ?: error("SQLite не вернул идентификатор сообщения")
        return Message(id, sessionId, role, content, at)
    }

    private fun insert(turn: Turn) {
        jdbc.sql(
            """
            INSERT INTO turn (session_id, number, created_at, strategy, model,
                              history_messages, included_messages, prompt_blocks, chars_sent, note,
                              prompt_tokens, cached_tokens, completion_tokens, total_tokens,
                              cost_usd, cost_source, latency_ms, finish_reason, error, error_status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        )
            // params(List) по той же причине: у неудачного хода токены равны null.
            .params(
                listOf(
                    turn.sessionId, turn.number, turn.at.toString(), turn.strategy.name, turn.model,
                    turn.historyMessages, turn.includedMessages, turn.promptBlocks, turn.charsSent, turn.note,
                    turn.promptTokens, turn.cachedPromptTokens, turn.completionTokens, turn.totalTokens,
                    turn.costUsd, turn.costSource?.name, turn.latencyMs, turn.finishReason,
                    turn.error, turn.errorStatus,
                ),
            )
            .update()
    }

    /** SQLite различает 0 и NULL, а `getInt` — нет: у него и то и другое ноль. Отсюда обёртки. */
    private fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }

    private fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }

    private fun ResultSet.doubleOrNull(column: String): Double? = getDouble(column).takeUnless { wasNull() }

    private fun ResultSet.toSession() = Session(
        id = getLong("id"),
        title = getString("title"),
        strategy = StrategyId.valueOf(getString("strategy")),
        windowSize = getInt("window_size"),
        createdAt = Instant.parse(getString("created_at")),
        parentId = longOrNull("parent_id"),
        forkedAfter = intOrNull("forked_after"),
    )

    private fun ResultSet.toMessage() = Message(
        id = getLong("id"),
        sessionId = getLong("session_id"),
        role = Role.valueOf(getString("role")),
        content = getString("content"),
        at = Instant.parse(getString("created_at")),
    )

    private fun ResultSet.toTurn() = Turn(
        number = getInt("number"),
        at = Instant.parse(getString("created_at")),
        sessionId = getLong("session_id"),
        strategy = StrategyId.valueOf(getString("strategy")),
        model = getString("model"),
        historyMessages = getInt("history_messages"),
        includedMessages = getInt("included_messages"),
        promptBlocks = getInt("prompt_blocks"),
        charsSent = getInt("chars_sent"),
        note = getString("note"),
        promptTokens = intOrNull("prompt_tokens"),
        cachedPromptTokens = intOrNull("cached_tokens"),
        completionTokens = intOrNull("completion_tokens"),
        totalTokens = intOrNull("total_tokens"),
        costUsd = doubleOrNull("cost_usd"),
        costSource = getString("cost_source")?.let { CostSource.valueOf(it) },
        latencyMs = getLong("latency_ms"),
        finishReason = getString("finish_reason"),
        error = getString("error"),
        errorStatus = intOrNull("error_status"),
        upkeep = toUpkeep(),
    )

    /** Строки обслуживания может не быть: у скользящего окна её нет никогда. */
    private fun ResultSet.toUpkeep(): Upkeep? {
        val note = getString("u_note") ?: return null
        return Upkeep(
            note = note,
            latencyMs = getLong("u_latency_ms"),
            promptTokens = intOrNull("u_prompt_tokens"),
            cachedPromptTokens = intOrNull("u_cached_tokens"),
            completionTokens = intOrNull("u_completion_tokens"),
            totalTokens = intOrNull("u_total_tokens"),
            costUsd = doubleOrNull("u_cost_usd"),
            costSource = getString("u_cost_source")?.let { CostSource.valueOf(it) },
            error = getString("u_error"),
        )
    }
}

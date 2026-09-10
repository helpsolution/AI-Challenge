package advent.day9.store

import advent.day9.chat.Message
import advent.day9.chat.Role
import advent.day9.chat.Summary
import advent.day9.chat.Turn
import advent.day9.chat.TurnKind
import advent.day9.context.ContextMode
import advent.day9.llm.CostSource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Instant

/**
 * Реализация на SQLite. Единственное место в приложении, которое знает SQL.
 *
 * Порядок задаёт `id`: `AUTOINCREMENT` монотонен, поэтому сортировка по нему — и есть
 * хронология. На `created_at` сортировку не завязываем: оба сообщения одного хода
 * получают близкие метки времени и могут совпасть. В этот день то же касается таблицы
 * обращений: у сворачивания истории номер хода тот же, что у хода, после которого оно
 * случилось, поэтому сортировать по `number` больше нельзя.
 */
@Repository
class SqliteChatStore(private val jdbc: JdbcClient) : ChatStore {

    override fun history(): List<Message> =
        jdbc.sql("SELECT id, role, content, created_at FROM message ORDER BY id")
            .query { rs, _ -> rs.toMessage() }
            .list()

    override fun messagesAfter(messageId: Long): List<Message> =
        jdbc.sql("SELECT id, role, content, created_at FROM message WHERE id > ? ORDER BY id")
            .param(messageId)
            .query { rs, _ -> rs.toMessage() }
            .list()

    override fun count(): Int =
        jdbc.sql("SELECT count(*) FROM message").query(Int::class.java).single()

    /** `length()` в SQLite считает символы, а не байты, — то же, что `String.length` в Kotlin. */
    override fun chars(): Int =
        jdbc.sql("SELECT coalesce(sum(length(content)), 0) FROM message").query(Int::class.java).single()

    override fun turns(): List<Turn> =
        jdbc.sql("SELECT * FROM turn ORDER BY id").query { rs, _ -> rs.toTurn() }.list()

    override fun latestSummary(): Summary? =
        jdbc.sql("SELECT * FROM summary ORDER BY version DESC LIMIT 1")
            .query { rs, _ -> rs.toSummary() }
            .optional()
            .orElse(null)

    override fun summaries(): List<Summary> =
        jdbc.sql("SELECT * FROM summary ORDER BY version").query { rs, _ -> rs.toSummary() }.list()

    @Transactional
    override fun saveTurn(question: Message, answer: Message, turn: Turn) {
        insert(question)
        insert(answer)
        insert(turn)
    }

    override fun saveFailedTurn(turn: Turn) {
        insert(turn)
    }

    @Transactional
    override fun saveSummary(summary: Summary, turn: Turn) {
        jdbc.sql(
            """
            INSERT INTO summary (version, created_at, covers_from_message_id, covers_upto_message_id,
                                 messages_folded, messages_covered, content, turn_number)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        )
            .params(
                summary.version, summary.at.toString(), summary.coversFromMessageId,
                summary.coversUptoMessageId, summary.foldedMessages, summary.coveredMessages,
                summary.content, summary.turnNumber,
            )
            .update()
        insert(turn)
    }

    @Transactional
    override fun clear() {
        jdbc.sql("DELETE FROM message").update()
        jdbc.sql("DELETE FROM summary").update()
        jdbc.sql("DELETE FROM turn").update()
    }

    private fun insert(message: Message) {
        jdbc.sql("INSERT INTO message (role, content, created_at) VALUES (?, ?, ?)")
            .params(message.role.name, message.content, message.at.toString())
            .update()
    }

    private fun insert(turn: Turn) {
        jdbc.sql(
            """
            INSERT INTO turn (number, kind, created_at, mode, provider, model, context_limit, max_tokens,
                              prompt_messages, history_total, history_chars, prompt_blocks, chars_sent,
                              persona_chars, summary_chars, tail_chars, summary_version,
                              prompt_tokens, cached_tokens, completion_tokens, total_tokens,
                              cost_usd, cost_source, latency_ms, finish_reason, truncated,
                              error, error_status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        )
            // params(List), а не params(vararg): у неудачного обращения токены равны null,
            // а vararg-перегрузка null не принимает.
            .params(
                listOf(
                    turn.number, turn.kind.name, turn.at.toString(), turn.mode.name, turn.provider, turn.model,
                    turn.contextLimit, turn.maxTokens, turn.promptMessages, turn.historyTotal, turn.historyChars,
                    turn.promptBlocks, turn.charsSent, turn.personaChars, turn.summaryChars, turn.tailChars,
                    turn.summaryVersion, turn.promptTokens, turn.cachedPromptTokens, turn.completionTokens,
                    turn.totalTokens, turn.costUsd, turn.costSource?.name, turn.latencyMs, turn.finishReason,
                    if (turn.truncated) 1 else 0, turn.error, turn.errorStatus,
                ),
            )
            .update()
    }

    /** SQLite различает 0 и NULL, а `getInt` — нет: у него и то и другое ноль. Отсюда обёртка. */
    private fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }

    private fun ResultSet.doubleOrNull(column: String): Double? = getDouble(column).takeUnless { wasNull() }

    private fun ResultSet.toMessage() = Message(
        role = Role.valueOf(getString("role")),
        content = getString("content"),
        at = Instant.parse(getString("created_at")),
        id = getLong("id"),
    )

    private fun ResultSet.toSummary() = Summary(
        version = getInt("version"),
        at = Instant.parse(getString("created_at")),
        coversFromMessageId = getLong("covers_from_message_id"),
        coversUptoMessageId = getLong("covers_upto_message_id"),
        foldedMessages = getInt("messages_folded"),
        coveredMessages = getInt("messages_covered"),
        content = getString("content"),
        turnNumber = getInt("turn_number"),
    )

    private fun ResultSet.toTurn() = Turn(
        number = getInt("number"),
        kind = TurnKind.valueOf(getString("kind")),
        at = Instant.parse(getString("created_at")),
        mode = ContextMode.valueOf(getString("mode")),
        provider = getString("provider"),
        model = getString("model"),
        contextLimit = getInt("context_limit"),
        maxTokens = getInt("max_tokens"),
        promptMessages = getInt("prompt_messages"),
        historyTotal = getInt("history_total"),
        historyChars = getInt("history_chars"),
        promptBlocks = getInt("prompt_blocks"),
        charsSent = getInt("chars_sent"),
        personaChars = getInt("persona_chars"),
        summaryChars = getInt("summary_chars"),
        tailChars = getInt("tail_chars"),
        summaryVersion = intOrNull("summary_version"),
        promptTokens = intOrNull("prompt_tokens"),
        cachedPromptTokens = intOrNull("cached_tokens"),
        completionTokens = intOrNull("completion_tokens"),
        totalTokens = intOrNull("total_tokens"),
        costUsd = doubleOrNull("cost_usd"),
        costSource = getString("cost_source")?.let { CostSource.valueOf(it) },
        latencyMs = getLong("latency_ms"),
        finishReason = getString("finish_reason"),
        truncated = getInt("truncated") == 1,
        error = getString("error"),
        errorStatus = intOrNull("error_status"),
    )
}

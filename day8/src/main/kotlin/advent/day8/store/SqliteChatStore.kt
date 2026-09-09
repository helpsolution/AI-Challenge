package advent.day8.store

import advent.day8.chat.Message
import advent.day8.chat.Role
import advent.day8.chat.Turn
import advent.day8.llm.CostSource
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
 * получают близкие метки времени и могут совпасть.
 */
@Repository
class SqliteChatStore(private val jdbc: JdbcClient) : ChatStore {

    override fun history(): List<Message> =
        jdbc.sql("SELECT role, content, created_at FROM message ORDER BY id")
            .query { rs, _ ->
                Message(
                    role = Role.valueOf(rs.getString("role")),
                    content = rs.getString("content"),
                    at = Instant.parse(rs.getString("created_at")),
                )
            }
            .list()

    override fun count(): Int =
        jdbc.sql("SELECT count(*) FROM message").query(Int::class.java).single()

    override fun turns(): List<Turn> =
        jdbc.sql("SELECT * FROM turn ORDER BY number").query { rs, _ -> rs.toTurn() }.list()

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
    override fun clear() {
        jdbc.sql("DELETE FROM message").update()
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
            INSERT INTO turn (number, created_at, provider, model, context_limit, max_tokens,
                              history_messages, prompt_blocks, chars_sent, prompt_tokens,
                              cached_tokens, completion_tokens, total_tokens, cost_usd, cost_source,
                              latency_ms, finish_reason, compressed, error, error_status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        )
            // params(List), а не params(vararg): у неудачного хода токены равны null,
            // а vararg-перегрузка null не принимает.
            .params(
                listOf(
                    turn.number, turn.at.toString(), turn.provider, turn.model, turn.contextLimit, turn.maxTokens,
                    turn.historyMessages, turn.promptBlocks, turn.charsSent, turn.promptTokens,
                    turn.cachedPromptTokens, turn.completionTokens, turn.totalTokens, turn.costUsd,
                    turn.costSource?.name, turn.latencyMs, turn.finishReason, if (turn.compressed) 1 else 0,
                    turn.error, turn.errorStatus,
                ),
            )
            .update()
    }

    /** SQLite различает 0 и NULL, а `getInt` — нет: у него и то и другое ноль. Отсюда обёртка. */
    private fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }

    private fun ResultSet.doubleOrNull(column: String): Double? = getDouble(column).takeUnless { wasNull() }

    private fun ResultSet.toTurn() = Turn(
        number = getInt("number"),
        at = Instant.parse(getString("created_at")),
        provider = getString("provider"),
        model = getString("model"),
        contextLimit = getInt("context_limit"),
        maxTokens = getInt("max_tokens"),
        historyMessages = getInt("history_messages"),
        promptBlocks = getInt("prompt_blocks"),
        charsSent = getInt("chars_sent"),
        promptTokens = intOrNull("prompt_tokens"),
        cachedPromptTokens = intOrNull("cached_tokens"),
        completionTokens = intOrNull("completion_tokens"),
        totalTokens = intOrNull("total_tokens"),
        costUsd = doubleOrNull("cost_usd"),
        costSource = getString("cost_source")?.let { CostSource.valueOf(it) },
        latencyMs = getLong("latency_ms"),
        finishReason = getString("finish_reason"),
        compressed = getInt("compressed") == 1,
        error = getString("error"),
        errorStatus = intOrNull("error_status"),
    )
}

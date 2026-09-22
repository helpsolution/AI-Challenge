package advent.users.storage

import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.support.AbstractFallbackSQLExceptionTranslator
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import java.sql.SQLException

/**
 * Spring не знает кодов ошибок SQLite: нарушение UNIQUE прилетает как UncategorizedSQLException,
 * то есть превращается в 500 вместо 409. Разбираем коды сами.
 *
 * Коды сравниваем через типизированный SQLiteErrorCode, а не по тексту сообщения:
 * текст драйвер может поменять в любой версии, номер кода — нет.
 */
class SqliteExceptionTranslator : AbstractFallbackSQLExceptionTranslator() {
    init {
        // всё, что не про ограничения SQLite, разбирает стандартный транслятор Spring
        fallbackTranslator = SQLExceptionSubclassTranslator()
    }

    override fun doTranslate(task: String, sql: String?, ex: SQLException): DataAccessException? {
        val code = (ex as? SQLiteException)?.resultCode ?: return null
        if (!code.isConstraintViolation()) return null
        val message = buildMessage(task, sql, ex)
        return when (code) {
            SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE,
            SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY,
            -> DuplicateKeyException(message, ex)

            else -> DataIntegrityViolationException(message, ex)
        }
    }

    /** Расширенный код SQLite — это базовый код в младшем байте плюс уточнение в старших. */
    private fun SQLiteErrorCode.isConstraintViolation(): Boolean =
        (code and 0xFF) == SQLiteErrorCode.SQLITE_CONSTRAINT.code
}

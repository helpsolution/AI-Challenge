package advent.rag.index

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** Что лежит в базе индекса: метаданные всего индекса и чанки своей стратегии. */
data class IndexContents<T>(
    val meta: Map<String, String>,
    val chunks: List<T>,
)

/** Общее у баз обеих стратегий: файл, таблица meta и формат вектора. Схема чанков у каждой своя. */
internal object Sqlite {
    /** Индекс пересобирается целиком: старый файл удаляется, база создаётся заново. */
    fun create(path: Path): Connection {
        Files.createDirectories(path.parent)
        Files.deleteIfExists(path)
        return DriverManager.getConnection("jdbc:sqlite:$path")
    }

    /** Без проверки драйвер молча создал бы пустой файл на месте несуществующего индекса. */
    fun open(path: Path): Connection {
        require(Files.exists(path)) { "Индекса $path ещё нет: соберите его через POST /api/index/rebuild" }
        return DriverManager.getConnection("jdbc:sqlite:$path")
    }

    fun writeMeta(db: Connection, meta: Map<String, Any>) {
        db.createStatement().use { it.executeUpdate("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)") }
        db.prepareStatement("INSERT INTO meta (key, value) VALUES (?, ?)").use { insert ->
            for ((key, value) in meta) {
                insert.setString(1, key)
                insert.setString(2, value.toString())
                insert.addBatch()
            }
            insert.executeBatch()
        }
    }

    fun readMeta(db: Connection): Map<String, String> =
        db.createStatement().use { statement ->
            statement.executeQuery("SELECT key, value FROM meta").use { rows ->
                buildMap { while (rows.next()) put(rows.getString("key"), rows.getString("value")) }
            }
        }

    /** Вектор — 768 чисел float32 подряд, little-endian: 3 КБ на чанк вместо ~10 КБ текста в JSON. */
    fun toBlob(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(vector)
        return buffer.array()
    }
}

package advent.rag.knowledge

import advent.rag.chunking.FixedSizeChunk
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * Файл базы знаний — схема fixed-size.db из day21: таблица чанков с вектором и таблица meta.
 * Вектор — float32 подряд, little-endian: 768 чисел nomic-embed-text занимают 3 КБ.
 */
internal class SqliteStore(private val path: Path) {

    fun exists(): Boolean = Files.exists(path)

    /** База пересобирается целиком: старый файл удаляется, новый пишется одной транзакцией. */
    fun write(chunks: List<IndexedChunk>, meta: Map<String, String>) {
        Files.createDirectories(path.parent)
        Files.deleteIfExists(path)
        connect().use { db ->
            db.autoCommit = false
            db.createStatement().use {
                it.executeUpdate(CHUNKS_SCHEMA)
                it.executeUpdate(META_SCHEMA)
            }
            db.prepareStatement(INSERT_CHUNK).use { insert ->
                for ((chunk, vector) in chunks) {
                    insert.setString(1, chunk.chunkId)
                    insert.setString(2, chunk.source)
                    insert.setString(3, chunk.title)
                    insert.setString(4, chunk.url)
                    insert.setInt(5, chunk.index)
                    insert.setInt(6, chunk.start)
                    insert.setInt(7, chunk.end)
                    insert.setString(8, chunk.text)
                    insert.setBytes(9, vector.toBlob())
                    insert.addBatch()
                }
                insert.executeBatch()
            }
            db.prepareStatement(INSERT_META).use { insert ->
                for ((key, value) in meta) {
                    insert.setString(1, key)
                    insert.setString(2, value)
                    insert.addBatch()
                }
                insert.executeBatch()
            }
            db.commit()
        }
    }

    fun readChunks(): List<IndexedChunk> =
        connect().use { db ->
            db.createStatement().use { statement ->
                statement.executeQuery(SELECT_CHUNKS).use { rows ->
                    buildList {
                        while (rows.next()) {
                            val chunk = FixedSizeChunk(
                                chunkId = rows.getString("chunk_id"),
                                source = rows.getString("source"),
                                title = rows.getString("title"),
                                url = rows.getString("url"),
                                index = rows.getInt("chunk_index"),
                                start = rows.getInt("start_offset"),
                                end = rows.getInt("end_offset"),
                                text = rows.getString("text"),
                            )
                            add(IndexedChunk(chunk, rows.getBytes("embedding").toVector()))
                        }
                    }
                }
            }
        }

    fun readMeta(): Map<String, String> =
        connect().use { db ->
            db.createStatement().use { statement ->
                statement.executeQuery("SELECT key, value FROM meta").use { rows ->
                    buildMap { while (rows.next()) put(rows.getString("key"), rows.getString("value")) }
                }
            }
        }

    private fun connect(): Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    private fun FloatArray.toBlob(): ByteArray {
        val buffer = ByteBuffer.allocate(size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(this)
        return buffer.array()
    }

    private fun ByteArray.toVector(): FloatArray {
        val floats = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(floats.remaining()).also { floats.get(it) }
    }

    private companion object {
        const val CHUNKS_SCHEMA = """
            CREATE TABLE chunks (
                chunk_id     TEXT PRIMARY KEY,
                source       TEXT NOT NULL,
                title        TEXT NOT NULL,
                url          TEXT,
                chunk_index  INTEGER NOT NULL,
                start_offset INTEGER NOT NULL,
                end_offset   INTEGER NOT NULL,
                text         TEXT NOT NULL,
                embedding    BLOB NOT NULL
            )"""
        const val META_SCHEMA = "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)"
        const val INSERT_CHUNK = """
            INSERT INTO chunks (chunk_id, source, title, url, chunk_index, start_offset, end_offset, text, embedding)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        const val INSERT_META = "INSERT INTO meta (key, value) VALUES (?, ?)"
        const val SELECT_CHUNKS = """
            SELECT chunk_id, source, title, url, chunk_index, start_offset, end_offset, text, embedding
            FROM chunks ORDER BY source, chunk_index"""
    }
}

package advent.rag.index

import advent.rag.chunking.FixedSizeChunk
import org.springframework.stereotype.Component
import java.nio.file.Path

/** Индекс стратегии «фиксированный размер» — файл fixed-size.db. */
@Component
class FixedSizeIndex(properties: IndexProperties) {
    val path: Path = properties.dir.resolve("fixed-size.db")

    fun write(chunks: List<Pair<FixedSizeChunk, FloatArray>>, meta: Map<String, Any>) {
        Sqlite.create(path).use { db ->
            db.autoCommit = false
            db.createStatement().use { it.executeUpdate(SCHEMA) }
            db.prepareStatement(INSERT).use { insert ->
                for ((chunk, vector) in chunks) {
                    insert.setString(1, chunk.chunkId)
                    insert.setString(2, chunk.source)
                    insert.setString(3, chunk.title)
                    insert.setString(4, chunk.url)
                    insert.setInt(5, chunk.index)
                    insert.setInt(6, chunk.start)
                    insert.setInt(7, chunk.end)
                    insert.setString(8, chunk.text)
                    insert.setBytes(9, Sqlite.toBlob(vector))
                    insert.addBatch()
                }
                insert.executeBatch()
            }
            Sqlite.writeMeta(db, meta)
            db.commit()
        }
    }

    /** Содержимое индекса без векторов: 768 чисел на чанк глазами всё равно не прочитать. */
    fun read(): IndexContents<FixedSizeChunk> =
        Sqlite.open(path).use { db ->
            val chunks = db.createStatement().use { statement ->
                statement.executeQuery(SELECT).use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                FixedSizeChunk(
                                    chunkId = rows.getString("chunk_id"),
                                    source = rows.getString("source"),
                                    title = rows.getString("title"),
                                    url = rows.getString("url"),
                                    index = rows.getInt("chunk_index"),
                                    start = rows.getInt("start_offset"),
                                    end = rows.getInt("end_offset"),
                                    text = rows.getString("text"),
                                ),
                            )
                        }
                    }
                }
            }
            IndexContents(Sqlite.readMeta(db), chunks)
        }

    private companion object {
        const val SCHEMA = """
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
        const val INSERT = """
            INSERT INTO chunks (chunk_id, source, title, url, chunk_index, start_offset, end_offset, text, embedding)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        const val SELECT = """
            SELECT chunk_id, source, title, url, chunk_index, start_offset, end_offset, text
            FROM chunks ORDER BY source, chunk_index"""
    }
}

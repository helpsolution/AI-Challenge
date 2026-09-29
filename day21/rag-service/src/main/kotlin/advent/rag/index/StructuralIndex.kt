package advent.rag.index

import advent.rag.chunking.StructuralChunk
import org.springframework.stereotype.Component
import java.nio.file.Path

/** Индекс стратегии «по структуре» — файл structural.db. От fixed-size.db отличается столбцом section. */
@Component
class StructuralIndex(properties: IndexProperties) {
    val path: Path = properties.dir.resolve("structural.db")

    fun write(chunks: List<Pair<StructuralChunk, FloatArray>>, meta: Map<String, Any>) {
        Sqlite.create(path).use { db ->
            db.autoCommit = false
            db.createStatement().use { it.executeUpdate(SCHEMA) }
            db.prepareStatement(INSERT).use { insert ->
                for ((chunk, vector) in chunks) {
                    insert.setString(1, chunk.chunkId)
                    insert.setString(2, chunk.source)
                    insert.setString(3, chunk.title)
                    insert.setString(4, chunk.url)
                    insert.setString(5, chunk.section)
                    insert.setInt(6, chunk.index)
                    insert.setInt(7, chunk.start)
                    insert.setInt(8, chunk.end)
                    insert.setString(9, chunk.text)
                    insert.setBytes(10, Sqlite.toBlob(vector))
                    insert.addBatch()
                }
                insert.executeBatch()
            }
            Sqlite.writeMeta(db, meta)
            db.commit()
        }
    }

    /** Содержимое индекса без векторов: 768 чисел на чанк глазами всё равно не прочитать. */
    fun read(): IndexContents<StructuralChunk> =
        Sqlite.open(path).use { db ->
            val chunks = db.createStatement().use { statement ->
                statement.executeQuery(SELECT).use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                StructuralChunk(
                                    chunkId = rows.getString("chunk_id"),
                                    source = rows.getString("source"),
                                    title = rows.getString("title"),
                                    url = rows.getString("url"),
                                    section = rows.getString("section"),
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
                section      TEXT,
                chunk_index  INTEGER NOT NULL,
                start_offset INTEGER NOT NULL,
                end_offset   INTEGER NOT NULL,
                text         TEXT NOT NULL,
                embedding    BLOB NOT NULL
            )"""
        const val INSERT = """
            INSERT INTO chunks (chunk_id, source, title, url, section, chunk_index, start_offset, end_offset, text, embedding)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        const val SELECT = """
            SELECT chunk_id, source, title, url, section, chunk_index, start_offset, end_offset, text
            FROM chunks ORDER BY source, chunk_index"""
    }
}

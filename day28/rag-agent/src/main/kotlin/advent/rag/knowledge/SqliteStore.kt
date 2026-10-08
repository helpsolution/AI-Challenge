package advent.rag.knowledge

import advent.rag.chunking.Chunk
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

internal class SqliteStore(private val path: Path) {
    fun exists() = Files.exists(path)
    private fun connect() = DriverManager.getConnection("jdbc:sqlite:$path")

    @Synchronized
    fun write(chunks: List<IndexedChunk>, meta: Map<String, String>) {
        path.toAbsolutePath().parent?.let(Files::createDirectories)
        connect().use { db ->
            // DELETE и INSERT внутри одной транзакции сохраняют старый индекс при ошибке.
            db.autoCommit = false
            try {
                db.createStatement().use {
                    it.executeUpdate("CREATE TABLE IF NOT EXISTS chunks (chunk_id TEXT PRIMARY KEY, source TEXT NOT NULL, title TEXT NOT NULL, url TEXT, section TEXT, context_prefix TEXT NOT NULL, context_suffix TEXT NOT NULL, chunk_index INTEGER NOT NULL, start_offset INTEGER NOT NULL, end_offset INTEGER NOT NULL, text TEXT NOT NULL, embedding BLOB NOT NULL)")
                    it.executeUpdate("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                    it.executeUpdate("DELETE FROM chunks")
                    it.executeUpdate("DELETE FROM meta")
                }
                db.prepareStatement("INSERT INTO chunks VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").use { insert ->
                    for ((chunk, vector) in chunks) {
                        insert.setString(1, chunk.chunkId); insert.setString(2, chunk.source)
                        insert.setString(3, chunk.title); insert.setString(4, chunk.url); insert.setString(5, chunk.section)
                        insert.setString(6, chunk.contextPrefix); insert.setString(7, chunk.contextSuffix)
                        insert.setInt(8, chunk.index); insert.setInt(9, chunk.start); insert.setInt(10, chunk.end)
                        insert.setString(11, chunk.text)
                        val buffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                        buffer.asFloatBuffer().put(vector)
                        insert.setBytes(12, buffer.array()); insert.addBatch()
                    }
                    insert.executeBatch()
                }
                db.prepareStatement("INSERT INTO meta VALUES (?, ?)").use { insert ->
                    meta.forEach { (key, value) -> insert.setString(1, key); insert.setString(2, value); insert.addBatch() }
                    insert.executeBatch()
                }
                db.commit()
            } catch (e: Exception) { db.rollback(); throw e }
        }
    }

    fun read(): Pair<List<IndexedChunk>, Map<String, String>> = connect().use { db ->
        db.autoCommit = false
        val chunks = db.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM chunks ORDER BY source, chunk_index").use { rows ->
                buildList {
                    while (rows.next()) {
                        val chunk = Chunk(
                            chunkId = rows.getString("chunk_id"), source = rows.getString("source"),
                            title = rows.getString("title"), url = rows.getString("url"), section = rows.getString("section"),
                            contextPrefix = rows.getString("context_prefix"), contextSuffix = rows.getString("context_suffix"),
                            index = rows.getInt("chunk_index"), start = rows.getInt("start_offset"), end = rows.getInt("end_offset"), text = rows.getString("text"))
                        val blob = rows.getBytes("embedding")
                        check(blob.isNotEmpty() && blob.size % 4 == 0) { "Повреждён вектор ${chunk.chunkId}" }
                        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                        add(IndexedChunk(chunk, FloatArray(buffer.remaining()).also { buffer.get(it) }))
                    }
                }
            }
        }
        val meta = db.createStatement().use { statement ->
            statement.executeQuery("SELECT key, value FROM meta").use { rows ->
                buildMap { while (rows.next()) put(rows.getString("key"), rows.getString("value")) }
            }
        }
        db.commit()
        chunks to meta
    }
}

package advent.habr.bot

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * Где кончилась прошлая сводка по таймеру: курсор из news_digest и время сводки.
 *
 * Хранится в файле, а не в памяти: бота перезапускают при каждой выкладке, и без файла первая
 * сводка после рестарта взяла бы «последние N минут по времени публикации» — часть статей пришла бы
 * второй раз, а опоздавшие в ленту не пришли бы вовсе.
 */
class CursorStore(private val dir: Path) {
    data class Saved(val cursor: Long, val at: Instant)

    private val file = dir.resolve("digest-cursor.txt")

    fun load(): Saved? = runCatching {
        val (cursor, at) = Files.readString(file).trim().split(" ")
        Saved(cursor.toLong(), Instant.parse(at))
    }.getOrNull()

    /** Запись через временный файл: оборванная на середине запись не оставит битый курсор. */
    fun save(saved: Saved) {
        Files.createDirectories(dir)
        val temp = dir.resolve("digest-cursor.txt.tmp")
        Files.writeString(temp, "${saved.cursor} ${saved.at}")
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

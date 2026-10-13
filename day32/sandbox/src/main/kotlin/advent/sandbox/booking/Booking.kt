package advent.sandbox.booking

import java.time.Instant
import java.util.UUID

data class Booking(
    val id: UUID,
    val roomId: String,
    val owner: String,
    val start: Instant,
    val end: Instant,
) {
    // Встречи впритык считаем пересечением: между ними нужно время на уборку.
    fun overlaps(from: Instant, to: Instant): Boolean = start <= to && from <= end
}

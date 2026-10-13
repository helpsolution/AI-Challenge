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
    // Полуинтервал [start, end): встреча до 11:00 не мешает встрече с 11:00.
    fun overlaps(from: Instant, to: Instant): Boolean = start < to && from < end
}
